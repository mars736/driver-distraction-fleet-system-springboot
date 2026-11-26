package vo.telemetry;

public record StoppedMotionCheck(long id, long vehicleId, long fleetId, long driverId, long stoppedAt,
                                 long firstSampledAt, long secondSampledAt, long detectedAt) { }
