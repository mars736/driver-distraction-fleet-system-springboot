package service.vehicle;

import common.exception.BusinessException;
import entity.User;
import lombok.RequiredArgsConstructor;
import mapper.OperationsRecordMapper;
import mapper.UserMapper;
import mapper.VehicleTelemetryMapper;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class DriverVehicleBindingGuard {
    private final OperationsRecordMapper records;
    private final UserMapper users;
    private final VehicleTelemetryMapper telemetry;

    /** 调用方在同一事务中先锁司机，再按 ID 顺序锁涉及的车辆。 */
    public void prepare(User before, Long target, Long fleetId, boolean enabled) {
        Long previous = before == null ? null : before.getVehicleId();
        boolean same = before != null && Objects.equals(previous, target) && Objects.equals(before.getFleetId(), fleetId);
        boolean wasEnabled = before != null && Boolean.TRUE.equals(before.getEnabled());
        if (previous != null && target != null && previous < target) {
            records.lockVehicle(previous);
        }
        var destination = target == null ? null : records.lockVehicle(target);
        if (previous != null && (target == null || previous > target)) {
            records.lockVehicle(previous);
        }
        if (target != null && (destination == null || !Objects.equals(fleetId, destination.getFleetId()))) {
            // 允许停用历史绑定异常的账号，便于清理旧数据。
            if (!same || enabled) { throw new BusinessException(400, "只能绑定同车队的有效车辆"); }
        }

        if (target != null && enabled && !(same && wasEnabled)) {
            Long driverId = before == null ? null : before.getId();
            if (users.findActiveDriversByVehicle(target).stream().anyMatch(id -> !id.equals(driverId))) {
                throw new BusinessException(409, "该车辆已有启用的绑定司机，请先解绑");
            }
        }

        if (previous != null && wasEnabled && (!same || !enabled)) {
            telemetry.clearMotionState(previous);
        }
        if (target != null && enabled && (!same || !wasEnabled) && (!Objects.equals(target, previous) || !wasEnabled)) {
            telemetry.clearMotionState(target);
        }
    }
}
