package service.vehicle;

import common.exception.BusinessException;
import common.response.PageResult;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import service.audit.OperationAction;
import service.audit.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import mapper.FleetMapper;
import mapper.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 车辆档案业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleMapper vehicleMapper;
    private final FleetMapper fleetMapper;
    private final OperationLogService audit;

    public PageResult<Vehicle> list(Long fleetId, Integer page, Integer pageSize) {
        int currentPage = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 100);
        int offset = (currentPage - 1) * size;

        List<Vehicle> list = vehicleMapper.list(fleetId, offset, size);
        long total = vehicleMapper.count(fleetId);
        return PageResult.of(list, total, currentPage, size);
    }

    public Vehicle getById(Long id) {
        Vehicle vehicle = vehicleMapper.selectById(id);
        if (vehicle == null) {
            throw new BusinessException(404, "车辆不存在");
        }
        return vehicle;
    }

    @Transactional
    public Vehicle create(Vehicle vehicle) {
        checkFleetExists(vehicle.getFleetId());
        if (vehicleMapper.countByPlateNo(vehicle.getPlateNo(), null) > 0) {
            throw new BusinessException("车牌号已存在");
        }
        if (vehicle.getMonitorEnabled() == null) {
            vehicle.setMonitorEnabled(1);
        }
        vehicleMapper.insert(vehicle);
        audit.success(OperationAction.VEHICLE_CREATE, vehicle.getId(), vehicle.getFleetId());
        log.info("新增车辆 id={}, plateNo={}", vehicle.getId(), vehicle.getPlateNo());
        return vehicle;
    }

    @Transactional
    public Vehicle update(Vehicle vehicle) {
        getById(vehicle.getId());
        checkFleetExists(vehicle.getFleetId());
        if (vehicleMapper.countByPlateNo(vehicle.getPlateNo(), vehicle.getId()) > 0) {
            throw new BusinessException("车牌号已存在");
        }
        vehicleMapper.update(vehicle);
        audit.success(OperationAction.VEHICLE_UPDATE, vehicle.getId(), vehicle.getFleetId());
        return vehicle;
    }

    @Transactional
    public void updateMonitorEnabled(Long id, Integer monitorEnabled) {
        Vehicle vehicle = getById(id);
        vehicleMapper.updateMonitorEnabled(id, monitorEnabled);
        audit.success(OperationAction.VEHICLE_MONITOR, id, vehicle.getFleetId(),
                Integer.valueOf(1).equals(monitorEnabled) ? "ENABLED" : "DISABLED");
    }

    @Transactional
    public void delete(Long id) {
        Vehicle vehicle = getById(id);
        vehicleMapper.deleteById(id);
        audit.success(OperationAction.VEHICLE_DELETE, id, vehicle.getFleetId());
        log.info("删除车辆 id={}", id);
    }

    private void checkFleetExists(Long fleetId) {
        if (fleetId == null || fleetMapper.selectById(fleetId) == null) {
            throw new BusinessException("所属车队不存在");
        }
    }
}
