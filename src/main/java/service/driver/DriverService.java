package service.driver;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import entity.User;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.FleetMapper;
import mapper.UserMapper;
import mapper.VehicleMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
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
    private final VehicleMapper vehicleMapper;
    private final PasswordEncoder passwordEncoder;

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

    @Transactional
    public User create(User user) {
        checkFleetExists(user.getFleetId());
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new BusinessException("手机号不能为空");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new BusinessException("密码不能为空");
        }
        checkEmployeeNoUnique(user.getEmployeeNo(), null);
        if (userMapper.countByUsername(user.getPhone()) > 0) {
            throw new BusinessException("该手机号已注册");
        }
        checkVehicleBelongsToFleet(user.getVehicleId(), user.getFleetId());

        user.setUsername(user.getPhone());
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setRole(CommonConstant.ROLE_DRIVER);
        user.setEnabled(true);
        user.setTokenVersion(0);
        userMapper.insert(user);
        log.info("新增司机 id={}, fleetId={}, employeeNo={}",
                user.getId(), user.getFleetId(), user.getEmployeeNo());
        return user;
    }

    @Transactional
    public User update(User user) {
        User existing = getById(user.getId());
        checkFleetExists(user.getFleetId());
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new BusinessException("手机号不能为空");
        }
        checkEmployeeNoUnique(user.getEmployeeNo(), user.getId());
        if (!existing.getPhone().equals(user.getPhone()) && userMapper.countByUsername(user.getPhone()) > 0) {
            throw new BusinessException("该手机号已注册");
        }
        checkVehicleBelongsToFleet(user.getVehicleId(), user.getFleetId());

        user.setUsername(user.getPhone());
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
        return user;
    }

    @Transactional
    public void updateStatus(Long id, boolean enabled) {
        User user = getById(id);
        userMapper.updateEnabled(id, enabled, user.getTokenVersion() + 1);
    }

    @Transactional
    public void updateVehicle(Long id, Long vehicleId) {
        User user = getById(id);
        checkVehicleBelongsToFleet(vehicleId, user.getFleetId());
        userMapper.updateVehicleId(id, vehicleId);
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        userMapper.deleteById(id);
        log.info("删除司机 id={}", id);
    }

    private void checkFleetExists(Long fleetId) {
        if (fleetId == null || fleetMapper.selectById(fleetId) == null) {
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

    private void checkVehicleBelongsToFleet(Long vehicleId, Long fleetId) {
        if (vehicleId == null) {
            return;
        }
        Vehicle vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            throw new BusinessException("绑定的车辆不存在");
        }
        if (!fleetId.equals(vehicle.getFleetId())) {
            throw new BusinessException("不能绑定其他车队的车辆");
        }
    }
}
