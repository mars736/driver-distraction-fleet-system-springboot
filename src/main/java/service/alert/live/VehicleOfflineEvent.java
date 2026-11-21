package service.alert.live;

public record VehicleOfflineEvent(long vehicleId, long fleetId, long sampledAt, long detectedAt) {
}
