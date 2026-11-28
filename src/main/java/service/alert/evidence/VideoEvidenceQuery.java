package service.alert.evidence;

import entity.AppealRecord;
import lombok.RequiredArgsConstructor;
import mapper.AlertVideoEvidenceMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import service.alert.AppealTargetType;
import service.alert.archive.WebHdfsArchiveClient;
import video.evidence.VideoEvidenceProperties;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

import static service.alert.evidence.AlertVideoEvidence.Status.*;

@Component
@RequiredArgsConstructor
public class VideoEvidenceQuery {
    private final VideoEvidenceProperties properties;
    private final AlertVideoEvidenceMapper mapper;
    private final ObjectProvider<WebHdfsArchiveClient> hdfs;

    public VideoEvidenceView view(AppealRecord appeal) {
        if (appeal.getTargetType() != AppealTargetType.ALERT) { return null; }
        var item = find(appeal);
        if (item == null) { return new VideoEvidenceView(UNAVAILABLE, null, null, null); }
        boolean playable = canPlay(item);
        return new VideoEvidenceView(item.status(), playable ? time(item.actualStartMs()) : null,
                playable ? time(item.actualEndMs()) : null,
                playable ? "/api/appeals/" + appeal.getId() + "/evidence-video" : null);
    }

    public AlertVideoEvidence playable(AppealRecord appeal) {
        var item = find(appeal);
        return canPlay(item) ? item : null;
    }

    public byte[] read(AlertVideoEvidence item) throws IOException, InterruptedException {
        return hdfs.getObject().readEvidence(properties.getDirectory(), item.alertId(),
                VideoEvidenceWorker.captureDate(item.capturedAtMs()), item.byteSize());
    }

    private AlertVideoEvidence find(AppealRecord appeal) {
        if (!properties.isEnabled() || appeal.getTargetType() != AppealTargetType.ALERT) { return null; }
        var item = mapper.find(appeal.getTargetId());
        return item != null && Objects.equals(appeal.getDriverId(), item.driverId()) ? item : null;
    }

    private static boolean canPlay(AlertVideoEvidence item) {
        return item != null && (item.status() == READY || item.status() == PARTIAL)
                && item.actualStartMs() != null && item.actualEndMs() != null
                && item.byteSize() != null && item.byteSize() > 0 && item.byteSize() <= VideoEvidenceWorker.MAX_BYTES;
    }

    private static LocalDateTime time(long time) {
        return Instant.ofEpochMilli(time).atZone(VideoEvidenceWorker.ZONE).toLocalDateTime();
    }
}
