package service.alert.archive;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record ArchiveEvent(long alertId, long revision, long recognitionId, Long driverId,
                           long vehicleId, Long fleetId, Long cameraId,
                           @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS") LocalDateTime capturedAt,
                           String recordType, String behaviorCode, String status, Source source,
                           @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS") LocalDateTime recordedAt) {
    public enum Source { CREATED, DIRECT_REVIEW, APPEAL, BACKFILL }
}
