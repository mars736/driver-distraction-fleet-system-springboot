package service.vehicle;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.security.UserContext;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import service.audit.OperationAction;
import service.audit.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import mapper.FleetMapper;
import mapper.VehicleMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import service.camera.CameraCaptureCoordinator.VehicleChanged;
import service.camera.CameraDriverBinding;

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
    private final ApplicationEventPublisher events;
    private final CameraDriverBinding cameraBindings;

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

    public CameraDriverBinding.Binding currentDriver(long id) {
        getById(id);
        return cameraBindings.current(id);
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
        events.publishEvent(new VehicleChanged(vehicle.getId(), false));
        return vehicle;
    }

    @Transactional
    public void updateMonitorEnabled(Long id, Integer monitorEnabled) {
        if (!Integer.valueOf(0).equals(monitorEnabled) && !Integer.valueOf(1).equals(monitorEnabled)) {
            throw new BusinessException(400, "车辆监控状态必须为 0 或 1");
        }
        Vehicle vehicle = getById(id);
        vehicleMapper.updateMonitorEnabled(id, monitorEnabled);
        audit.success(OperationAction.VEHICLE_MONITOR, id, vehicle.getFleetId(),
                Integer.valueOf(1).equals(monitorEnabled) ? "ENABLED" : "DISABLED");
        events.publishEvent(new VehicleChanged(id, monitorEnabled == 0));
    }

    @Transactional
    public void delete(Long id) {
        Vehicle vehicle = getById(id);
        vehicleMapper.deleteById(id);
        events.publishEvent(new VehicleChanged(id, true));
        audit.success(OperationAction.VEHICLE_DELETE, id, vehicle.getFleetId());
        log.info("删除车辆 id={}", id);
    }

    private void checkFleetExists(Long fleetId) {
        if (fleetId == null || fleetMapper.lockById(fleetId) == null) {
            throw new BusinessException("所属车队不存在");
        }
    }
}
