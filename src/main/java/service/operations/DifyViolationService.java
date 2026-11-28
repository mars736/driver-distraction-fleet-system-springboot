package service.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import dto.operations.DifyAgent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.DifyAgentMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.alert.archive.ArchiveEvent;
import service.alert.archive.WebHdfsArchiveClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static service.operations.OperationsAccess.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DifyViolationService {
    private static final int MAX_FILES = 2000;
    private static final int MAX_ALERTS = 200_000;
    private final Semaphore reads = new Semaphore(1);
    private final DifyAgentMapper mapper;
    private final ObjectProvider<WebHdfsArchiveClient> hdfs;
    private final ObjectMapper json;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ViolationSummary summary(LocalDate startDate, LocalDate endDate, Long fleetId, Long driverId,
                                    int top, int page, int size) {
        admin();
        var at = now();
        var end = endDate == null ? at.toLocalDate().plusDays(1) : endDate;
        var start = startDate == null ? end.minusYears(1) : startDate;
        dateRange(start, end);
        var scope = new Scope(start.atStartOfDay(), end.atStartOfDay(), fleetId, driverId);
        var client = hdfs.getIfAvailable();
        check(client != null, 503, "后端未配置 HDFS 告警归档");
        check(reads.tryAcquire(), 503, "正在统计另一批归档数据，请稍后重试");
        try {
            var coverage = mapper.archiveCoverage(scope.start(), scope.end(), fleetId, driverId);
            var files = mapper.archiveFiles(scope.start(), scope.end(), fleetId, driverId, MAX_FILES + 1);
            check(files.size() <= MAX_FILES, 422, "归档文件超过单次统计上限，请缩小日期范围");
            var latest = loadLatest(client, files, scope);
            var confirmed = latest.values().stream().filter(DifyViolationService::confirmed)
                    .sorted(Comparator.comparing(ArchiveEvent::capturedAt).thenComparingLong(ArchiveEvent::alertId).reversed())
                    .toList();
            var counts = confirmed.stream().collect(Collectors.groupingBy(ArchiveEvent::driverId, Collectors.counting()));
            var ranked = counts.entrySet().stream()
                    .sorted(Map.Entry.<Long, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(top).toList();
            var rows = confirmed.stream().skip(offset(page, size)).limit(size).toList();
            var ids = new HashSet<Long>();
            ranked.forEach(entry -> ids.add(entry.getKey()));
            rows.forEach(event -> ids.add(event.driverId()));
            Map<Long, String> names = ids.isEmpty() ? Map.of() : mapper.driverLabels(ids).stream()
                    .collect(Collectors.toMap(DriverLabel::id, DriverLabel::name));
            return new ViolationSummary(start, end, at, coverage, files.size(), confirmed.size(),
                    months(start, end, confirmed), behaviors(confirmed),
                    ranked.stream().map(entry -> new DriverCount(entry.getKey(), names.get(entry.getKey()), entry.getValue())).toList(),
                    page, size, rows.stream().map(event -> new ViolationRow(event.alertId(), event.driverId(),
                            names.get(event.driverId()), event.fleetId(), event.vehicleId(), event.capturedAt(),
                            event.behaviorCode(), event.recordType())).toList());
        } finally {
            reads.release();
        }
    }

    private Map<Long, ArchiveEvent> loadLatest(WebHdfsArchiveClient client, List<ArchiveFile> files, Scope scope) {
        var latest = new HashMap<Long, ArchiveEvent>();
        if (files.isEmpty()) { return latest; }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        try (var executor = Executors.newFixedThreadPool(6)) {
            // 分组读取，避免同时在内存中堆积整个一年的 JSONL。
            for (int index = 0; index < files.size(); index += 6) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { throw new TimeoutException("读取归档超时"); }
                var jobs = files.subList(index, Math.min(index + 6, files.size())).stream()
                        .<Callable<List<ArchiveEvent>>>map(file -> () -> read(client, file, scope)).toList();
                for (var result : executor.invokeAll(jobs, remaining, TimeUnit.NANOSECONDS)) {
                    for (var event : result.get()) {
                        latest.merge(event.alertId(), event, DifyViolationService::latestVersion);
                    }
                    check(latest.size() <= MAX_ALERTS, 422, "告警记录超过单次统计上限，请缩小日期范围");
                }
            }
            return latest;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "读取 HDFS 被中断，请稍后重试");
        } catch (ExecutionException | TimeoutException | IllegalStateException exception) {
            log.warn("Agent 告警归档读取失败，异常类型={}", exception.getClass().getSimpleName());
            throw new BusinessException(503, "无法完整读取 HDFS 告警归档，请稍后重试");
        }
    }

    private List<ArchiveEvent> read(WebHdfsArchiveClient client, ArchiveFile file, Scope scope)
            throws IOException, InterruptedException {
        var bytes = client.readAlertBatch(file.id(), file.partitionDate());
        var result = new ArrayList<ArchiveEvent>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
            var event = json.readValue(line, ArchiveEvent.class);
            if (event == null || event.alertId() <= 0 || event.revision() < 0
                    || event.capturedAt() == null || event.status() == null || event.recordType() == null) {
                throw new IOException("归档事件缺少必需字段");
            }
            if (scope.contains(event)) { result.add(event); }
        }
        return result;
    }

    static ArchiveEvent latestVersion(ArchiveEvent first, ArchiveEvent second) {
        if (first.revision() == second.revision() && !first.equals(second)) {
            throw new IllegalStateException("同一告警归档版本内容不一致");
        }
        return first.revision() >= second.revision() ? first : second;
    }

    static boolean confirmed(ArchiveEvent event) {
        return "CONFIRMED".equals(event.status())
                && ("SMS_ALERT".equals(event.recordType()) || "BACKLOG".equals(event.recordType()));
    }

    private static List<Bucket> months(LocalDate start, LocalDate end, List<ArchiveEvent> rows) {
        var counts = rows.stream().collect(Collectors.groupingBy(
                event -> YearMonth.from(event.capturedAt()), Collectors.counting()));
        var months = new ArrayList<Bucket>();
        var last = YearMonth.from(end.minusDays(1));
        for (var month = YearMonth.from(start); !month.isAfter(last); month = month.plusMonths(1)) {
            months.add(new Bucket(month.toString(), counts.getOrDefault(month, 0L)));
        }
        return months;
    }

    private static List<Bucket> behaviors(List<ArchiveEvent> rows) {
        Function<ArchiveEvent, String> code = event -> event.behaviorCode() == null ? "UNCLASSIFIED" : event.behaviorCode();
        return rows.stream().collect(Collectors.groupingBy(code, Collectors.counting())).entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new Bucket(entry.getKey(), entry.getValue())).toList();
    }

    private record Scope(LocalDateTime start, LocalDateTime end, Long fleetId, Long driverId) {
        boolean contains(ArchiveEvent event) {
            return !event.capturedAt().isBefore(start) && event.capturedAt().isBefore(end) && event.driverId() != null
                    && (fleetId == null || fleetId.equals(event.fleetId()))
                    && (driverId == null || driverId.equals(event.driverId()));
        }
    }
}
