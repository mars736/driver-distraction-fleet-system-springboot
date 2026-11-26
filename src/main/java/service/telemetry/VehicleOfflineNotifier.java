package service.telemetry;

import lombok.RequiredArgsConstructor;
import mapper.OperationsRecordMapper;
import mapper.UserMapper;
import mapper.VehicleTelemetryMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.alert.live.VehicleOfflineEvent;

import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleOfflineNotifier {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final VehicleTelemetryMapper telemetry;
    private final OperationsRecordMapper records;
    private final UserMapper users;
    private final ApplicationEventPublisher events;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void notifyIfStillOffline(long vehicleId, long driverId, long taskId, long now) {
        if (records.lockUser(driverId) == null) { return; }
        var vehicle = records.lockVehicle(vehicleId);
        if (vehicle == null || !Integer.valueOf(1).equals(vehicle.getMonitorEnabled())
                || !users.findActiveDriversByVehicle(vehicleId).equals(List.of(driverId))) {
            return;
        }

        var task = telemetry.lockRunningTask(taskId, vehicleId, driverId, vehicle.getFleetId());
        if (task == null) { return; }

        var position = telemetry.findLatestForDriver(vehicleId, driverId);
        long departure = task.departedAt().atZone(ZONE).toInstant().toEpochMilli();
        if (position == null || position.sampledAt() < departure
                || position.sampledAt() >= now - VehicleTelemetryService.MAX_SPEED_AGE_MS) {
            return;
        }
        if (telemetry.claimOfflineNotice(taskId, vehicleId, driverId, position.sampledAt(), now) == 1) {
            events.publishEvent(new VehicleOfflineEvent(vehicleId, vehicle.getFleetId(), position.sampledAt(), now,
                    taskId, driverId));
        }
    }
}
