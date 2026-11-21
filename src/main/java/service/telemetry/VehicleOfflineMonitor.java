package service.telemetry;

import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import mapper.VehicleTelemetryMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import service.vehicle.VehicleService;

import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@EnableScheduling
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
                    .collect(Collectors.toMap(Vehicle::getId, Function.identity()));
            if (!monitored.isEmpty()) {
                var candidates = telemetry.findOfflineCandidates(monitored.keySet().stream().toList(),
                        now - VehicleTelemetryService.MAX_SPEED_AGE_MS);
                for (var position : candidates) {
                    notifier.notifyIfStillOffline(monitored.get(position.vehicleId()), position.driverId(), now);
                }
            }
            if ((long) page * PAGE_SIZE >= batch.getTotal()) {
                return;
            }
        }
    }
}
