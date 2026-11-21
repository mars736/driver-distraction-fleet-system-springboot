package service.alert;

import entity.AlertTimelineFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.AlertTimelineMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/** 在告警消费线程上处理相邻帧，不改动抽帧进程或推理队列。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlertTimelineCapture {
    private static final int MAX_CAMERAS = 64;
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final long MAX_GAP_MS = 30_000;
    private final AlertTimelineMapper mapper;
    private final KeyFrameStore files;
    private final LinkedHashMap<Long, Sample> recent = new LinkedHashMap<>();
    private final ReentrantLock[] locks = IntStream.range(0, MAX_CAMERAS)
            .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    /** 数据库提交后才发布新缓存，锁一直保留到提交回调和回滚清理都结束。 */
    void lockUntilCompletion(CapturedInference input) {
        if (input.cameraId() == null && input.vehicleId() == null) { return; }
        int camera = input.cameraId() == null ? -1 : stripe(input.cameraId(), 1);
        int vehicle = input.vehicleId() == null ? -1 : stripe(input.vehicleId(), 0);
        int first = camera < 0 || vehicle < 0 ? Math.max(camera, vehicle) : Math.min(camera, vehicle);
        int second = Math.max(camera, vehicle);
        locks[first].lock();
        if (second != first) { locks[second].lock(); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (second != first) { locks[second].unlock(); }
                locks[first].unlock();
            }
        });
    }

    private int stripe(long id, int namespace) {
        return Math.floorMod(31 * Long.hashCode(id) + namespace, locks.length);
    }

    private record Sample(Long vehicleId, Long driverId, long capturedAt, long frameId,
                          LocalDateTime time, byte[] jpeg, List<Long> alerts) {
        boolean isBefore(Sample other) {
            return capturedAt < other.capturedAt || (capturedAt == other.capturedAt && frameId < other.frameId);
        }

        boolean sameAssignment(Sample other) {
            return Objects.equals(vehicleId, other.vehicleId) && Objects.equals(driverId, other.driverId);
        }
    }

    void capture(CapturedInference input, LocalDateTime time, List<Long> alertIds, boolean broken) {
        if (input.cameraId() == null && !broken) { return; }
        byte[] jpeg = input.jpeg();
        if (jpeg != null && (jpeg.length == 0 || jpeg.length > MAX_FRAME_BYTES)) { jpeg = null; }
        var current = new Sample(input.vehicleId(), input.driverId(), input.capturedAt(), input.frameId(),
                time, jpeg, List.copyOf(alertIds));
        Sample previous;
        synchronized (recent) { previous = recent.get(input.cameraId()); }

        // 换司机、换车辆或出现断档时不拼接视频，避免将上一位司机的图片交给下一位。
        if (!broken && previous != null && previous.isBefore(current)
                && current.capturedAt() - previous.capturedAt() <= MAX_GAP_MS
                && current.driverId() != null && current.vehicleId() != null
                && previous.sameAssignment(current)) {
            for (long id : current.alerts()) { save(id, -1, previous); }
            for (long id : previous.alerts()) { save(id, 1, current); }
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                synchronized (recent) {
                    if (broken && current.vehicleId() != null) {
                        recent.values().removeIf(sample -> current.vehicleId().equals(sample.vehicleId())
                                && sample.isBefore(current));
                    }
                    if (input.cameraId() == null) { return; }
                    Sample latest = recent.get(input.cameraId());
                    if (latest != null && !latest.isBefore(current)) { return; }
                    recent.remove(input.cameraId());
                    recent.put(input.cameraId(), current);
                    if (recent.size() > MAX_CAMERAS) { recent.pollFirstEntry(); }
                }
            }
        });
    }

    private void save(long alertId, int offset, Sample sample) {
        if (sample.jpeg() == null) { return; }
        String path;
        try { path = files.saveJpeg(sample.capturedAt(), sample.jpeg()); }
        catch (IOException e) {
            log.warn("未能保存告警相邻帧，将保留告警：alertId={}，offset={}", alertId, offset);
            return;
        }
        files.deleteOnRollback(path);
        mapper.insert(alertId, new AlertTimelineFrame(offset, sample.time(), path));
    }
}
