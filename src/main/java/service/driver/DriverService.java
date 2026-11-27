package service.driver;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import entity.Fleet;
import entity.User;
import mapper.OperationsRecordMapper;
import lombok.RequiredArgsConstructor;
import service.audit.OperationAction;
import service.audit.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import mapper.FleetMapper;
import mapper.UserMapper;
import mapper.WorkforceMapper;
import org.springframework.dao.DuplicateKeyException;
import service.vehicle.DriverVehicleBindingGuard;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 司机档案业务。司机账号统一存在 sys_user 表里，用 role='DRIVER' 区分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverService {

    private final UserMapper userMapper;
    private final FleetMapper fleetMapper;
    private final OperationsRecordMapper records;
    private final WorkforceMapper workforce;
    private final DriverVehicleBindingGuard bindings;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogService audit;

    public PageResult<User> list(Long fleetId, String keyword, Integer page, Integer pageSize) {
        int currentPage = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 100);
        int offset = (currentPage - 1) * size;

        List<User> list = userMapper.listDrivers(fleetId, keyword, offset, size);
        long total = userMapper.countDrivers(fleetId, keyword);
        return PageResult.of(list, total, currentPage, size);
    }

    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null || !CommonConstant.ROLE_DRIVER.equals(user.getRole())) {
            throw new BusinessException(404, "司机不存在");
        }
        return user;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public User create(User user) {
        requireEnabledFleet(lockFleet(user.getFleetId()));
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new BusinessException("手机号不能为空");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new BusinessException("密码不能为空");
        }
        checkEmployeeNoUnique(user.getEmployeeNo(), null);
        if (user.getVehicleId() != null) {
            throw new BusinessException(400, "请先建档，再通过司机绑车接口分配车辆");
        }

        user.setUsername(user.getEmployeeNo());
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setRole(CommonConstant.ROLE_DRIVER);
        user.setEnabled(true);
        user.setTokenVersion(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException duplicate) {
            throw new DriverEmployeeNoConflict();
        }
        audit.success(OperationAction.DRIVER_CREATE, user.getId(), user.getFleetId());
        log.info("新增司机 id={}, fleetId={}, employeeNo={}",
                user.getId(), user.getFleetId(), user.getEmployeeNo());
        return user;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public User update(User user) {
        var targetFleet = lockFleet(user.getFleetId());
        User existing = lockDriver(user.getId());
        if (!Objects.equals(existing.getFleetId(), user.getFleetId())) {
            requireEnabledFleet(targetFleet);
            if (existing.getVehicleId() != null || workforce.runningDriver(existing.getId())) {
                throw new BusinessException(409, "转队前须完成执行中的运输任务并解绑原车辆");
            }
        }
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new BusinessException("手机号不能为空");
        }
        checkEmployeeNoUnique(user.getEmployeeNo(), user.getId());

        user.setUsername(user.getEmployeeNo());
        boolean passwordChanged = user.getPassword() != null && !user.getPassword().isBlank();
        if (passwordChanged) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(existing.getPassword());
        }
        try {
            userMapper.updateDriverProfile(user);
        } catch (DuplicateKeyException duplicate) {
            throw new DriverEmployeeNoConflict();
        }
        if (passwordChanged) {
            userMapper.updatePassword(user.getId(), user.getPassword(), existing.getTokenVersion() + 1);
        }
        audit.success(OperationAction.DRIVER_UPDATE, user.getId(), user.getFleetId());
        return userMapper.selectById(user.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateStatus(Long id, boolean enabled) {
        User user = lockDriver(id);
        bindings.prepare(user, user.getVehicleId(), user.getFleetId(), enabled);
        userMapper.updateEnabled(id, enabled, user.getTokenVersion() + 1);
        audit.success(OperationAction.DRIVER_STATUS, id, user.getFleetId(), enabled ? "ENABLED" : "DISABLED");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateVehicle(Long id, Long vehicleId) {
        User user = lockDriver(id);
        Long previous = user.getVehicleId();
        if (Objects.equals(previous, vehicleId)) { return; }
        bindings.prepare(user, vehicleId, user.getFleetId(), Boolean.TRUE.equals(user.getEnabled()));
        if (workforce.runningDriver(id) || previous != null && workforce.running(id, previous, 0)
                || vehicleId != null && workforce.running(id, vehicleId, 0)) {
            throw new BusinessException(409, "司机或关联车辆有执行中的运输任务，请完成后再调整绑定");
        }
        if (vehicleId != null) {
            var at = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            var today = at.toLocalDate().atStartOfDay();
            if (!Boolean.TRUE.equals(user.getEnabled()) || !workforce.punchedOnDuty(id, at)
                    || workforce.onLeave(id, at) || workforce.restOverlap(id, today, today.plusDays(1))) {
                throw new BusinessException(409, "司机须启用且在岗才能分配车辆");
            }
        }
        userMapper.updateVehicleId(id, vehicleId);
        audit.success(OperationAction.DRIVER_BIND_VEHICLE, id, user.getFleetId(), vehicleId == null ? "UNBOUND" : "BOUND");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long id) {
        User user = lockDriver(id);
        bindings.prepare(user, null, user.getFleetId(), false);
        if (userMapper.hasDriverHistory(id)) {
            throw new BusinessException(409, "司机已有业务或操作历史，请停用账号以保留历史记录");
        }
        userMapper.deleteById(id);
        audit.success(OperationAction.DRIVER_DELETE, id, user.getFleetId());
        log.info("删除司机 id={}", id);
    }

    private Fleet lockFleet(Long fleetId) {
        var fleet = fleetId == null ? null : fleetMapper.lockById(fleetId);
        if (fleet == null) { throw new BusinessException("所属车队不存在"); }
        return fleet;
    }

    private void requireEnabledFleet(Fleet fleet) {
        if (!Boolean.TRUE.equals(fleet.getEnabled())) {
            throw new BusinessException(409, "所属车队已停用");
        }
    }

    private void checkEmployeeNoUnique(String employeeNo, Long excludeId) {
        if (employeeNo == null || employeeNo.isBlank()) {
            throw new BusinessException("工号不能为空");
        }
        var account = userMapper.selectByUsername(employeeNo);
        if (userMapper.countByEmployeeNoExclude(employeeNo, excludeId) > 0
                || account != null && !Objects.equals(account.getId(), excludeId)) {
            throw new DriverEmployeeNoConflict();
        }
    }

    private User lockDriver(long id) {
        User driver = records.lockUser(id);
        if (driver == null || !CommonConstant.ROLE_DRIVER.equals(driver.getRole())) {
            throw new BusinessException(404, "司机不存在");
        }
        return driver;
    }
}
