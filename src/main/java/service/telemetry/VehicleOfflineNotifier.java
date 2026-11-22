package service.telemetry;

import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import mapper.VehicleMapper;
import mapper.VehicleTelemetryMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.alert.live.VehicleOfflineEvent;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleOfflineNotifier {
    private final VehicleTelemetryMapper telemetry;
    private final VehicleMapper vehicles;
    private final ApplicationEventPublisher events;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void notifyIfStillOffline(Vehicle vehicle, long driverId, long now) {
        if (telemetry.lockDriverBinding(driverId, vehicle.getId()) == null) {
            return;
        }

        var monitored = vehicles.selectById(vehicle.getId());
        if (monitored == null || !Integer.valueOf(1).equals(monitored.getMonitorEnabled())) { return; }

        var latest = telemetry.findLatestPositions(List.of(vehicle.getId()));
        if (latest.isEmpty()) {
            return;
        }

        var position = latest.getFirst();
        if (position.driverId() != driverId
                || position.sampledAt() >= now - VehicleTelemetryService.MAX_SPEED_AGE_MS) {
            return;
        }

        if (telemetry.claimOfflineNotice(vehicle.getId(), driverId, position.sampledAt(), now) == 1) {
            events.publishEvent(new VehicleOfflineEvent(vehicle.getId(), monitored.getFleetId(),
                    position.sampledAt(), now));
        }
    }
}
