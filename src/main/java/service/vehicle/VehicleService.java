package service.vehicle;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.security.UserContext;
import entity.Fleet;
import entity.User;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import service.audit.OperationAction;
import service.audit.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import mapper.CameraMapper;
import mapper.FleetMapper;
import mapper.UserMapper;
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
    private final UserMapper userMapper;
    private final CameraMapper cameraMapper;
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

    public Vehicle getReadableById(long id) {
        var actor = UserContext.get();
        if (CommonConstant.ROLE_PLATFORM_ADMIN.equals(actor.role())) { return getById(id); }
        if (!CommonConstant.ROLE_DRIVER.equals(actor.role()) || actor.userId() == null) {
            throw new BusinessException(403, "无权限查询车辆");
        }
        // 一条查询同时验证当前绑定和查详情，换绑后无需重新登录。
        Vehicle vehicle = vehicleMapper.selectBoundToDriver(id, actor.userId());
        if (vehicle == null) { throw new BusinessException(404, "车辆不存在"); }
        return vehicle;
    }

    @Transactional
    public Vehicle create(Vehicle vehicle) {
        requireEnabledFleet(vehicle.getFleetId());
        if (vehicleMapper.countByPlateNo(vehicle.getPlateNo(), null) > 0) {
            throw new BusinessException("车牌号已存在");
        }
        validateMonitor(vehicle.getMonitorEnabled());
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
        Vehicle existing = getById(vehicle.getId());
        if (!existing.getFleetId().equals(vehicle.getFleetId())) {
            // 转队：新车队必须启用，且不能留下跨车队的绑定司机
            requireEnabledFleet(vehicle.getFleetId());
            for (User bound : userMapper.listByVehicleId(vehicle.getId())) {
                if (!vehicle.getFleetId().equals(bound.getFleetId())) {
                    throw new BusinessException("该车还绑着其他车队的司机，请先解绑再转队");
                }
            }
        } else {
            requireFleet(vehicle.getFleetId());
        }
        if (vehicleMapper.countByPlateNo(vehicle.getPlateNo(), vehicle.getId()) > 0) {
            throw new BusinessException("车牌号已存在");
        }
        validateMonitor(vehicle.getMonitorEnabled());
        vehicleMapper.update(vehicle);
        audit.success(OperationAction.VEHICLE_UPDATE, vehicle.getId(), vehicle.getFleetId());
        return vehicle;
    }

    @Transactional
    public void updateMonitorEnabled(Long id, Integer monitorEnabled) {
        validateMonitor(monitorEnabled);
        Vehicle vehicle = getById(id);
        vehicleMapper.updateMonitorEnabled(id, monitorEnabled);
        audit.success(OperationAction.VEHICLE_MONITOR, id, vehicle.getFleetId(),
                Integer.valueOf(1).equals(monitorEnabled) ? "ENABLED" : "DISABLED");
    }

    @Transactional
    public void delete(Long id) {
        Vehicle vehicle = getById(id);
        if (!userMapper.listByVehicleId(id).isEmpty()) {
            throw new BusinessException("该车还绑着司机，请先解绑");
        }
        if (cameraMapper.countByVehicleId(id) > 0) {
            throw new BusinessException("该车还关联着摄像头，请先处理");
        }
        vehicleMapper.deleteById(id);
        audit.success(OperationAction.VEHICLE_DELETE, id, vehicle.getFleetId());
        log.info("删除车辆 id={}", id);
    }

    private Fleet requireFleet(Long fleetId) {
        if (fleetId == null) {
            throw new BusinessException("所属车队不能为空");
        }
        Fleet fleet = fleetMapper.lockById(fleetId);
        if (fleet == null) {
            throw new BusinessException("所属车队不存在");
        }
        return fleet;
    }

    private void requireEnabledFleet(Long fleetId) {
        Fleet fleet = requireFleet(fleetId);
        if (!Boolean.TRUE.equals(fleet.getEnabled())) {
            throw new BusinessException("所属车队已停用");
        }
    }

    private void validateMonitor(Integer monitorEnabled) {
        if (monitorEnabled != null && monitorEnabled != 0 && monitorEnabled != 1) {
            throw new BusinessException("监控开关只能是 0 或 1");
        }
    }
}
