package service.telemetry;

import common.exception.BusinessException;
import common.response.PageResult;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import mapper.VehicleTelemetryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.vehicle.VehicleService;
import vo.telemetry.VehicleLiveStatus;
import vo.telemetry.VehicleLivePosition;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VehicleLiveStatusService {
    private final VehicleService vehicles;
    private final VehicleTelemetryMapper telemetry;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<VehicleLiveStatus> list(Long fleetId, int page, int pageSize) {
        if ((fleetId != null && fleetId < 1) || page < 1 || pageSize < 1
                || pageSize > 100 || (long) (page - 1) * pageSize > Integer.MAX_VALUE) {
            throw new BusinessException(400, "车队或分页参数无效");
        }

        long now = System.currentTimeMillis();
        PageResult<Vehicle> vehiclePage = vehicles.list(fleetId, page, pageSize);
        if (vehiclePage.getList().isEmpty()) {
            return PageResult.of(List.of(), vehiclePage.getTotal(), page, pageSize);
        }

        List<Long> vehicleIds = vehiclePage.getList().stream().map(Vehicle::getId).toList();
        Map<Long, VehicleLivePosition> positions = telemetry.findLivePositions(vehicleIds).stream()
                .collect(Collectors.toMap(VehicleLivePosition::vehicleId, Function.identity()));
        List<VehicleLiveStatus> statuses = vehiclePage.getList().stream()
                .map(vehicle -> status(vehicle, positions.get(vehicle.getId()), now))
                .toList();
        return PageResult.of(statuses, vehiclePage.getTotal(), page, pageSize);
    }

    private VehicleLiveStatus status(Vehicle vehicle, VehicleLivePosition position, long now) {
        String locationStatus;
        if (position.driverCount() > 1) { locationStatus = "BINDING_CONFLICT"; }
        else if (position.driverId() == null) { locationStatus = "UNBOUND"; }
        else if (position.sampledAt() == null) { locationStatus = "NO_SAMPLE"; }
        else if (position.sampledAt() < now - VehicleTelemetryService.MAX_SPEED_AGE_MS) { locationStatus = "STALE"; }
        else { locationStatus = "ONLINE"; }

        boolean online = "ONLINE".equals(locationStatus);
        return new VehicleLiveStatus(vehicle.getId(), vehicle.getFleetId(), vehicle.getPlateNo(),
                online, position.sampledAt(), position.latitude(), position.longitude(),
                online ? position.speedKmh() : null, position.driverId(), locationStatus,
                now, position.receivedAt(), "GCJ-02", online ? position.movementCheck(vehicle.getFleetId()) : null);
    }
}
