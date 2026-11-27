package vo.telemetry;

public record VehiclePosition(Long vehicleId, Long driverId, Long sampledAt, Double latitude,
                              Double longitude, Double speedKmh, Long receivedAt, String coordinateSystem) { }
