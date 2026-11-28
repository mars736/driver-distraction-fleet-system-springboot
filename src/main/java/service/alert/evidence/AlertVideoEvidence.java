package service.alert.evidence;

public record AlertVideoEvidence(long alertId, long cameraId, long driverId, String streamId, long capturedAtMs,
                                 Status status, Long actualStartMs, Long actualEndMs, Integer byteSize) {
    public enum Status { PROCESSING, READY, PARTIAL, UNAVAILABLE }
}
