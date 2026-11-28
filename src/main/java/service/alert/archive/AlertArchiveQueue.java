package service.alert.archive;

import lombok.RequiredArgsConstructor;
import mapper.AlertArchiveMapper;
import mapper.AlertArchiveMapper.Batch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlertArchiveQueue {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final AlertArchiveMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long alertId, ArchiveEvent.Source source) {
        // 归档版本沿用告警行锁。只有有效告警类型进入归档。
        if (mapper.advanceRevision(alertId) == 1
                && mapper.insertEvent(alertId, source, LocalDateTime.now(ZONE)) != 1) {
            throw new IllegalStateException("保存告警归档事件失败");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int backfill(LocalDateTime at, int size) {
        mapper.initializeBackfill();
        var cursor = mapper.lockBackfill();
        if (cursor.lastId() >= cursor.throughId()) { return 0; }
        var ids = mapper.backfillIds(cursor.lastId(), cursor.throughId(), size);
        long end = ids.size() < size ? cursor.throughId() : ids.getLast();
        int inserted = mapper.backfillEvents(cursor.lastId(), end, at);
        mapper.advanceBackfill(end);
        return inserted;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Lease claim(LocalDateTime at, int size) {
        var batch = mapper.lockDueBatch(at);
        if (batch == null) {
            var events = mapper.lockUnassignedEvents(size);
            if (events.isEmpty()) { return null; }
            batch = new Batch(UUID.randomUUID().toString(), at.toLocalDate());
            mapper.insertBatch(batch, at);
            if (mapper.attachEvents(batch.id(), events) != events.size()) {
                throw new IllegalStateException("归档事件与批次关联失败");
            }
        }

        String token = UUID.randomUUID().toString();
        mapper.leaseBatch(batch.id(), token, at.plusMinutes(2));
        return new Lease(batch, token);
    }

    public List<ArchiveEvent> events(Lease lease) { return mapper.events(lease.batch().id()); }

    public boolean complete(Lease lease, LocalDateTime at) {
        return mapper.complete(lease.batch().id(), lease.token(), at) == 1;
    }

    public void retry(Lease lease, LocalDateTime at, String error) {
        mapper.retry(lease.batch().id(), lease.token(), at.plusMinutes(1), error);
    }

    public record Lease(Batch batch, String token) { }
}
