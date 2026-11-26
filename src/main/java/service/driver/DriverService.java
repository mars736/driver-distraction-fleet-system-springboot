package service.driver;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import entity.User;
import mapper.OperationsRecordMapper;
import lombok.RequiredArgsConstructor;
import service.audit.OperationAction;
import service.audit.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import mapper.FleetMapper;
import mapper.UserMapper;
import service.vehicle.DriverVehicleBindingGuard;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        checkFleetExists(user.getFleetId());
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new BusinessException("手机号不能为空");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new BusinessException("密码不能为空");
        }
        checkEmployeeNoUnique(user.getEmployeeNo(), null);
        bindings.prepare(null, user.getVehicleId(), user.getFleetId(), true);

        user.setUsername(user.getEmployeeNo());
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setRole(CommonConstant.ROLE_DRIVER);
        user.setEnabled(true);
        user.setTokenVersion(0);
        userMapper.insert(user);
        audit.success(OperationAction.DRIVER_CREATE, user.getId(), user.getFleetId());
        log.info("新增司机 id={}, fleetId={}, employeeNo={}",
                user.getId(), user.getFleetId(), user.getEmployeeNo());
        return user;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public User update(User user) {
        checkFleetExists(user.getFleetId());
        User existing = lockDriver(user.getId());
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new BusinessException("手机号不能为空");
        }
        checkEmployeeNoUnique(user.getEmployeeNo(), user.getId());
        bindings.prepare(existing, user.getVehicleId(), user.getFleetId(), Boolean.TRUE.equals(existing.getEnabled()));

        user.setUsername(user.getEmployeeNo());
        boolean passwordChanged = user.getPassword() != null && !user.getPassword().isBlank();
        if (passwordChanged) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(existing.getPassword());
        }
        userMapper.updateDriverProfile(user);
        if (passwordChanged) {
            userMapper.updatePassword(user.getId(), user.getPassword(), existing.getTokenVersion() + 1);
        }
        audit.success(OperationAction.DRIVER_UPDATE, user.getId(), user.getFleetId());
        return user;
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
        bindings.prepare(user, vehicleId, user.getFleetId(), Boolean.TRUE.equals(user.getEnabled()));
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

    private void checkFleetExists(Long fleetId) {
        if (fleetId == null || fleetMapper.lockById(fleetId) == null) {
            throw new BusinessException("所属车队不存在");
        }
    }

    private void checkEmployeeNoUnique(String employeeNo, Long excludeId) {
        if (employeeNo == null || employeeNo.isBlank()) {
            throw new BusinessException("工号不能为空");
        }
        if (userMapper.countByEmployeeNoExclude(employeeNo, excludeId) > 0) {
            throw new BusinessException("该工号已存在");
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
