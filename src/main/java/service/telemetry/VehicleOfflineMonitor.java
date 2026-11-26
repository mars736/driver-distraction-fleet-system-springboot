package service.telemetry;

import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import mapper.VehicleTelemetryMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import service.vehicle.VehicleService;

@Component
@ConditionalOnProperty(name = "telemetry.offline-monitor.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class VehicleOfflineMonitor {
    private static final int PAGE_SIZE = 100;

    private final VehicleService vehicles;
    private final VehicleTelemetryMapper telemetry;
    private final VehicleOfflineNotifier notifier;

    @Scheduled(fixedDelayString = "${telemetry.offline-monitor.interval-ms:5000}",
            initialDelayString = "${telemetry.offline-monitor.interval-ms:5000}")
    public void check() {
        long now = System.currentTimeMillis();
        for (int page = 1; ; page++) {
            var batch = vehicles.list(null, page, PAGE_SIZE);
            var monitored = batch.getList().stream()
                    .filter(vehicle -> Integer.valueOf(1).equals(vehicle.getMonitorEnabled()))
                    .map(Vehicle::getId).toList();
            if (!monitored.isEmpty()) {
                var candidates = telemetry.findOfflineCandidates(monitored,
                        now - VehicleTelemetryService.MAX_SPEED_AGE_MS);
                for (var position : candidates) {
                    notifier.notifyIfStillOffline(position.vehicleId(), position.driverId(), position.taskId(), now);
                }
            }
            if ((long) page * PAGE_SIZE >= batch.getTotal()) {
                return;
            }
        }
    }
}
