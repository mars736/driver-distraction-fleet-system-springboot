package service.rating;

import common.exception.BusinessException;
import entity.User;
import lombok.RequiredArgsConstructor;
import mapper.DriverSafetyRatingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.rating.DriverSafetyRating.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DriverSafetyRatingStore {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int LIMIT = 100_000;
    private final DriverSafetyRatingProperties properties;
    private final DriverSafetyRatingMapper mapper;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Batch manual() {
        if (!properties.isEnabled()) { throw new BusinessException(503, "长期安全评级作业未启用"); }
        lock();
        if (mapper.active() != null) { throw new BusinessException(409, "已有长期安全评级批次在执行"); }
        return create(now(), null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Batch next(LocalDateTime now) {
        lock();
        Batch active = mapper.active();
        if (active != null) { return active; }
        LocalDate week = weekAt(now);
        return mapper.hasWeek(week) ? null : create(now, week);
    }

    private Batch create(LocalDateTime at, LocalDate week) {
        String id = UUID.randomUUID().toString();
        mapper.create(id, at, week);
        mapper.setActive(id);
        return mapper.find(id);
    }

    public Batch get(String id) {
        Batch batch = mapper.find(id);
        if (batch == null) { throw new BusinessException(404, "长期安全评级批次不存在"); }
        return batch;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public boolean freeze(Batch expected, LocalDateTime now) {
        lock();
        Batch batch = mapper.active();
        if (batch == null || !expected.id().equals(batch.id()) || batch.status() != Status.PENDING) { return false; }
        if (batch.frozenAt() != null) { return true; }
        if (!mapper.backfillComplete() || mapper.hasPendingArchive(batch.dataAsOf())) { return false; }

        var roster = mapper.rosterAt(batch.dataAsOf());
        var files = mapper.archivedFiles(batch.dataAsOf());
        if (roster.size() > LIMIT || files.size() > LIMIT) {
            throw new IllegalArgumentException("评级快照超过 100000 名司机或归档文件");
        }
        forChunks(roster, rows -> mapper.saveRoster(batch.id(), rows));
        forChunks(files, rows -> mapper.saveFiles(batch.id(), rows));
        mapper.freeze(batch.id(), now);
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publish(String id, List<Result> results, String stage1, String stage2, LocalDateTime now) {
        lock();
        Batch batch = mapper.active();
        if (batch == null || !batch.id().equals(id) || batch.status() != Status.RUNNING) { return; }
        var drivers = mapper.loadRoster(id).stream().collect(Collectors.toMap(Roster::driverId, Function.identity()));
        if (drivers.size() != results.size()) { throw new IllegalArgumentException("评级结果数量与冻结名单不符"); }
        for (Result result : results) {
            Roster driver = drivers.remove(result.driverId());
            if (driver == null || driver.hasDrivingHistory() != result.hasDrivingHistory()) {
                throw new IllegalArgumentException("评级结果包含重复、未知司机或不匹配的驾驶历史");
            }
        }
        forChunks(results, rows -> mapper.saveResults(id, rows));
        mapper.progress(id, stage1, stage2);
        mapper.published(id, now);
        mapper.setPublished(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void fail(String id, String message) {
        lock();
        Batch batch = mapper.active();
        if (batch == null || !batch.id().equals(id)) { return; }
        mapper.failed(id, message);
        mapper.setActive(null);
    }

    public void attach(List<User> drivers) {
        if (drivers.isEmpty()) { return; }
        var ids = drivers.stream().map(User::getId).toList();
        var ratings = mapper.currentRatings(ids).stream().collect(Collectors.toMap(DriverView::driverId, DriverView::view));
        drivers.forEach(driver -> driver.setSafetyRating(ratings.get(driver.getId())));
    }

    private void lock() {
        if (mapper.lockControl() == null) { throw new IllegalStateException("长期评级控制行尚未迁移"); }
    }

    static LocalDate weekAt(LocalDateTime now) {
        LocalDate monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return now.isBefore(monday.atTime(2, 0)) ? monday.minusWeeks(1) : monday;
    }

    static LocalDateTime now() { return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MILLIS); }

    private static <T> void forChunks(List<T> rows, Consumer<List<T>> consumer) {
        for (int start = 0; start < rows.size(); start += 250) {
            consumer.accept(rows.subList(start, Math.min(start + 250, rows.size())));
        }
    }
}
