package service.alert.evidence;

import lombok.RequiredArgsConstructor;
import mapper.AlertVideoEvidenceMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import video.FfmpegManager;
import video.evidence.VideoEvidenceProperties;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoEvidenceQueue {
    public static final long BEFORE_MS = 5_000;
    public static final long AFTER_MS = 10_000;
    private final VideoEvidenceProperties properties;
    private final AlertVideoEvidenceMapper mapper;

    public record Requested(long alertId, Long cameraId, Long driverId, String streamId, long capturedAt) { }
    record Work(AlertVideoEvidence evidence, String token) { }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Requested event) {
        if (properties.isEnabled() && event.cameraId() != null && event.driverId() != null
                && FfmpegManager.isManagedStream(event.streamId())) {
            mapper.enqueue(event.alertId(), event.cameraId(), event.driverId(), event.streamId(),
                    event.capturedAt(), event.capturedAt() + AFTER_MS + 4_000);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Work claim(long now) {
        var item = mapper.lockDue(now);
        if (item == null) { return null; }
        var token = UUID.randomUUID().toString();
        mapper.lease(item.alertId(), token, now + 120_000);
        return new Work(item, token);
    }
}
