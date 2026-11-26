package vo.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class DashboardView {
    private DashboardView() { }

    public record Scope(LocalDateTime start, LocalDateTime end, Long fleetId, Long vehicleId, Long driverId) { }
    public record FleetSize(long vehicleCount, long driverCount) { }
    public record Bucket(String category, long frameCount) { }
    public record Behavior(String behaviorCode, long frameCount, BigDecimal share) { }
    public record Recognitions(long totalFrames, long normalFrames, long distractedFrames, long oodFrames,
                               long unclassifiedFrames, BigDecimal oodRate, List<Behavior> behaviors) { }
    public record Alerts(long smsAlertCount, long backlogCount, long speedUnknownCount, long revokedViolationCount) {
        @JsonProperty("activeViolationCount")
        public long activeViolationCount() {
            return smsAlertCount + backlogCount;
        }
    }
    public record RecentAlert(long id, Long vehicleId, LocalDateTime capturedAt,
                              String recordType, String behaviorCode, String status) { }
    public record Overview(LocalDate date, Long fleetId, FleetSize fleetSize,
                           Recognitions recognitions, Alerts alerts, List<RecentAlert> recentAlerts) { }
    public record FleetRange(Long fleetId, String fleetName, LocalDateTime start, LocalDateTime end, FleetSize fleetSize,
                             Recognitions recognitions, Alerts alerts) { }
    public record VehicleDaily(LocalDate date, long vehicleId, String plateNo, Long fleetId,
                               Recognitions recognitions, Alerts alerts, int currentHourDistractionEvents) { }
    public record DriverDaily(LocalDate date, long driverId, Recognitions recognitions, Alerts alerts,
                              List<RecentAlert> recentAlerts) { }
}
