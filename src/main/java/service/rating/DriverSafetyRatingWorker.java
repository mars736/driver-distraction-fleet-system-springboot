package service.rating;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.DriverSafetyRatingMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import service.alert.archive.AlertArchiveProperties;
import service.alert.archive.WebHdfsArchiveClient;
import service.rating.DriverSafetyRating.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "driver-safety-rating.enabled", havingValue = "true")
public class DriverSafetyRatingWorker {
    private static final int MAX_DATA_BYTES = 16 * 1024 * 1024;
    private final DriverSafetyRatingStore store;
    private final DriverSafetyRatingMapper mapper;
    private final DriverSafetyRatingProperties properties;
    private final AlertArchiveProperties archive;
    private final WebHdfsArchiveClient hdfs;
    private ScheduledExecutorService executor;

    @PostConstruct
    void start() {
        if (properties.getDirectory() == null || !properties.getDirectory().matches("(?:/[A-Za-z0-9_-]+)+")) {
            throw new IllegalArgumentException("HDFS 评级工作目录无效");
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "driver-safety-rating");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 10, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) { executor.shutdownNow(); }
    }

    private void tick() {
        try {
            runOnce(DriverSafetyRatingStore.now());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ex) {
            log.warn("长期安全评级任务等待下一轮，异常类型={}", ex.getClass().getSimpleName());
        }
    }

    void runOnce(LocalDateTime now) throws IOException, InterruptedException {
        Batch batch = store.next(now);
        if (batch == null) { return; }
        if (!now.isBefore(batch.dataAsOf().plusHours(6))) {
            store.fail(batch.id(), "评级批次超时，请检查告警归档或 YARN 后手动重算");
            return;
        }
        try {
            if (batch.status() == Status.PENDING) {
                if (store.freeze(batch, now)) { export(batch); }
                return;
            }
            complete(batch, now);
        } catch (IllegalArgumentException ex) {
            store.fail(batch.id(), "评级快照或 YARN 结果校验失败");
            log.warn("长期评级数据校验失败，batchId={}，说明={}", batch.id(), ex.getMessage());
        }
    }

    private void export(Batch batch) throws IOException, InterruptedException {
        String id = batch.id();
        String manifest = mapper.loadFiles(id).stream().map(file -> archive.getDirectory() + "/date="
                + file.partitionDate() + "/batch-" + file.id() + ".jsonl").collect(Collectors.joining("\n"));
        String roster = mapper.loadRoster(id).stream().map(driver -> driver.driverId() + "\t" + driver.registeredAt()
                + "\t" + driver.hasDrivingHistory()).collect(Collectors.joining("\n"));
        write(id, "manifest.txt", manifest);
        write(id, "roster.tsv", roster);
        write(id, "cutoff.txt", batch.dataAsOf().toString());
        write(id, "ready.ok", "ready");
        mapper.running(id);
    }

    private void complete(Batch batch, LocalDateTime now) throws IOException, InterruptedException {
        String id = batch.id();
        String outcome = read(id, "outcome.tsv", 512);
        if (outcome == null) {
            String one = application(read(id, "stage1-job.txt", 128));
            String two = application(read(id, "stage2-job.txt", 128));
            if (one != null || two != null) { mapper.progress(id, one, two); }
            return;
        }
        String[] row = outcome.strip().split("\t", -1);
        if (row.length != 4 || !row[1].matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("作业状态文件格式无效");
        }
        String one = application(row[2]);
        String two = application(row[3]);
        if ("FAILED".equals(row[0])) {
            mapper.progress(id, one, two);
            store.fail(id, "YARN 评级作业失败，请按作业 ID 检查日志后手动重算");
            return;
        }
        if (!"SUCCEEDED".equals(row[0]) || one == null || two == null) {
            throw new IllegalArgumentException("作业成功状态或作业 ID 缺失");
        }
        String scores = read(id, "attempts/" + row[1] + "/scores/part-r-00000", MAX_DATA_BYTES);
        if (scores == null) { throw new IllegalArgumentException("已完成的评级批次缺少结果文件"); }
        List<Result> rows = scores.lines().map(DriverSafetyRatingWorker::parseResult).toList();
        store.publish(id, rows, one, two, now);
    }

    static Result parseResult(String text) {
        String[] row = text.split("\t", -1);
        if (row.length != 5 || !row[2].matches("[0-9]{1,3}\\.[0-9]")
                || (!"true".equals(row[4]) && !"false".equals(row[4]))) {
            throw new IllegalArgumentException("评级记录字段格式无效");
        }
        long driver = Long.parseLong(row[0]);
        long events = Long.parseLong(row[1]);
        BigDecimal score = new BigDecimal(row[2]);
        var level = Level.valueOf(row[3]);
        if (driver <= 0 || events < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0 || Level.of(score) != level
                || (events == 0 && score.compareTo(BigDecimal.valueOf(100)) != 0)) {
            throw new IllegalArgumentException("评级记录分数、等级或数量不一致");
        }
        return new Result(driver, events, score, level, Boolean.parseBoolean(row[4]));
    }

    private static String application(String raw) {
        if (raw == null || "-".equals(raw.strip())) { return null; }
        String id = raw.strip();
        if (!id.matches("application_[0-9]+_[0-9]+")) { throw new IllegalArgumentException("YARN 作业编号无效"); }
        return id;
    }

    private void write(String id, String name, String content) throws IOException, InterruptedException {
        byte[] payload = (content.isEmpty() ? content : content + "\n").getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_DATA_BYTES) { throw new IllegalArgumentException("单个评级快照文件超限"); }
        hdfs.publishRatingInput(properties.getDirectory(), id, name, payload);
    }

    private String read(String id, String name, int limit) throws IOException, InterruptedException {
        byte[] bytes = hdfs.readRatingFile(properties.getDirectory(), id, name, limit);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
