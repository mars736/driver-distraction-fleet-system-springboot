package vo.telemetry;

import java.util.List;

public record VehicleTrack(long vehicleId, long startTime, long endTime, long totalPoints,
                           double totalDistanceKm, int segmentCount, int page, int pageSize, long snapshotId,
                           String coordinateSystem, List<Point> points) {
    public record Point(long driverId, long sampledAt, double latitude, double longitude, Double speedKmh,
                        int segment, double distanceFromPreviousKm, String coordinateSystem) { }
}
