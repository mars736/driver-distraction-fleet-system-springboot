package service.telemetry;

import common.exception.BusinessException;
import dto.telemetry.VehicleTelemetryRequest;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import mapper.FleetMapper;
import mapper.OperationsRecordMapper;
import mapper.UserMapper;
import mapper.VehicleMapper;
import mapper.VehicleTelemetryMapper;
import mapper.VehicleTelemetryMapper.MotionState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.alert.VehicleSpeedProvider;
import vo.telemetry.StoppedMotionCheck;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class VehicleTelemetryService implements VehicleSpeedProvider {
    static final long MAX_SPEED_AGE_MS = Duration.ofSeconds(15).toMillis();
    private static final long MAX_REPORT_DELAY_MS = Duration.ofMinutes(5).toMillis();
    private static final long MAX_CLOCK_LEAD_MS = Duration.ofSeconds(5).toMillis();
    private static final double MOVING_SPEED_KMH = 5.0;

    private final VehicleTelemetryMapper mapper;
    private final VehicleMapper vehicles;
    private final FleetMapper fleets;
    private final OperationsRecordMapper records;
    private final UserMapper users;
    private final ApplicationEventPublisher events;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void report(long driverId, VehicleTelemetryRequest request) {
        long now = System.currentTimeMillis();
        long sampledAt = request.sampledAt();
        if (sampledAt < now - MAX_REPORT_DELAY_MS || sampledAt > now + MAX_CLOCK_LEAD_MS) {
            throw new BusinessException(400, "定位采样时间已过期或超前，请校准设备时间");
        }

        // 停运操作先锁车队，任务和绑车操作先锁司机再锁车辆。
        var initial = vehicles.selectById(request.vehicleId());
        var fleet = initial == null ? null : fleets.lockById(initial.getFleetId());
        var driver = records.lockUser(driverId);
        var vehicle = records.lockVehicle(request.vehicleId());
        if (fleet == null || vehicle == null || driver == null || !"DRIVER".equals(driver.getRole())
                || !Boolean.TRUE.equals(driver.getEnabled()) || !Objects.equals(driver.getVehicleId(), vehicle.getId())
                || !Objects.equals(driver.getFleetId(), vehicle.getFleetId())
                || !Objects.equals(vehicle.getFleetId(), fleet.getId())) {
            throw new BusinessException(409, "车辆与当前司机的绑定不匹配");
        }

        var previousSample = mapper.findLatestForDriver(vehicle.getId(), driverId);
        Double speedKmh = request.speedMps() == null
                ? null : request.speedMps().multiply(BigDecimal.valueOf(3.6)).doubleValue();
        mapper.insertSample(vehicle.getId(), driverId, sampledAt, request.latitude().doubleValue(),
                request.longitude().doubleValue(), speedKmh, now);
        if (previousSample != null && sampledAt <= previousSample.sampledAt()) { return; }

        if (!users.findActiveDriversByVehicle(vehicle.getId()).equals(List.of(driverId))) {
            mapper.clearMotionState(vehicle.getId());
            return;
        }
        Long stoppedAt = mapper.stoppedAt(fleet.getId());
        if (stoppedAt == null || now < stoppedAt || sampledAt < stoppedAt || sampledAt < now - MAX_SPEED_AGE_MS) {
            mapper.clearMotionState(vehicle.getId());
            return;
        }
        evaluateStoppedMotion(vehicle, driverId, stoppedAt, sampledAt, speedKmh, now);
    }

    private void evaluateStoppedMotion(Vehicle vehicle, long driverId, long stoppedAt, long sampledAt,
                                       Double speedKmh, long receivedAt) {
        var previous = mapper.motionState(vehicle.getId());
        boolean fast = speedKmh != null && speedKmh > MOVING_SPEED_KMH;
        Long checkId = null;
        if (fast && previous != null && previous.fleetId() == vehicle.getFleetId()
                && previous.driverId() == driverId && previous.stoppedAt() == stoppedAt
                && previous.aboveThreshold() && sampledAt - previous.lastSampledAt() <= MAX_SPEED_AGE_MS) {
            checkId = previous.checkId();
            if (checkId == null) {
                var check = new StoppedMotionCheck(0, vehicle.getId(), vehicle.getFleetId(), driverId, stoppedAt,
                        previous.lastSampledAt(), sampledAt, receivedAt);
                mapper.insertMotionCheck(check);
                checkId = records.generatedId();
                events.publishEvent(new StoppedMotionCheck(checkId, vehicle.getId(), vehicle.getFleetId(), driverId,
                        stoppedAt, previous.lastSampledAt(), sampledAt, receivedAt));
            }
        }
        mapper.saveMotionState(new MotionState(vehicle.getId(), vehicle.getFleetId(), driverId, stoppedAt,
                sampledAt, fast, checkId));
    }

    @Override
    public Double speedKmh(Long vehicleId, Long driverId, long capturedAt) {
        if (vehicleId == null || driverId == null) { return null; }
        return mapper.findSpeedAt(vehicleId, driverId, capturedAt - MAX_SPEED_AGE_MS, capturedAt);
    }
}
