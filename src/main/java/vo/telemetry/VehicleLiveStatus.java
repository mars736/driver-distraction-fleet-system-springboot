package vo.telemetry;

public record VehicleLiveStatus(Long vehicleId, Long fleetId, String plateNo,
                                boolean online, Long sampledAt, Double latitude,
                                Double longitude, Double speedKmh) {
}
