package vo.telemetry;

public record VehicleLivePosition(long vehicleId, Long driverId, int driverCount, Long sampledAt,
                                  Double latitude, Double longitude, Double speedKmh, Long receivedAt,
                                  Long movementCheckId, Long stoppedAt, Long firstSampledAt,
                                  Long secondSampledAt, Long detectedAt, String coordinateSystem) {
    public StoppedMotionCheck movementCheck(long fleetId) {
        return movementCheckId == null ? null : new StoppedMotionCheck(movementCheckId, vehicleId, fleetId, driverId,
                stoppedAt, firstSampledAt, secondSampledAt, detectedAt);
    }
}
