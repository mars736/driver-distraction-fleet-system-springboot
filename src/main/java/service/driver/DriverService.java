package service.driver;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import entity.Driver;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.CompanyMapper;
import mapper.DriverMapper;
import mapper.VehicleMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 司机业务（M0）。
 * 司机同时是登录账号：密码统一 BCrypt 存储，接口响应里不会带出密码（实体上用 WRITE_ONLY 控制）。
 * 证照字段留到 §23 与 P13 有结论后再加。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverService {

    private final DriverMapper driverMapper;
    private final CompanyMapper companyMapper;
    private final VehicleMapper vehicleMapper;
    private final PasswordEncoder passwordEncoder;

    public PageResult<Driver> list(Long companyId, String keyword, Integer page, Integer pageSize) {
        int currentPage = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 100);
        int offset = (currentPage - 1) * size;

        List<Driver> list = driverMapper.list(companyId, keyword, offset, size);
        long total = driverMapper.count(companyId, keyword);
        return PageResult.of(list, total, currentPage, size);
    }

    public Driver getById(Long id) {
        Driver driver = driverMapper.selectById(id);
        if (driver == null) {
            throw new BusinessException(404, "司机不存在");
        }
        return driver;
    }

    @Transactional
    public Driver create(Driver driver) {
        if (companyMapper.selectById(driver.getCompanyId()) == null) {
            throw new BusinessException("所属车队不存在");
        }
        if (driver.getPassword() == null || driver.getPassword().isBlank()) {
            throw new BusinessException("密码不能为空");
        }
        checkEmployeeNoUnique(driver.getCompanyId(), driver.getEmployeeNo(), null);
        checkVehicleBelongsToCompany(driver.getVehicleId(), driver.getCompanyId());

        driver.setPassword(passwordEncoder.encode(driver.getPassword()));
        if (driver.getAccountStatus() == null) {
            driver.setAccountStatus(CommonConstant.STATUS_ENABLED);
        }
        driverMapper.insert(driver);
        log.info("新增司机 id={}, companyId={}, employeeNo={}",
                driver.getId(), driver.getCompanyId(), driver.getEmployeeNo());
        return driver;
    }

    @Transactional
    public Driver update(Driver driver) {
        Driver existing = getById(driver.getId());
        if (companyMapper.selectById(driver.getCompanyId()) == null) {
            throw new BusinessException("所属车队不存在");
        }
        checkEmployeeNoUnique(driver.getCompanyId(), driver.getEmployeeNo(), driver.getId());
        checkVehicleBelongsToCompany(driver.getVehicleId(), driver.getCompanyId());

        // 密码留空表示不修改，沿用原密文
        if (driver.getPassword() == null || driver.getPassword().isBlank()) {
            driver.setPassword(existing.getPassword());
        } else {
            driver.setPassword(passwordEncoder.encode(driver.getPassword()));
        }
        driverMapper.update(driver);
        return driver;
    }

    @Transactional
    public void updateAccountStatus(Long id, Integer accountStatus) {
        getById(id);
        driverMapper.updateAccountStatus(id, accountStatus);
    }

    /** 绑定/解绑车辆；vehicleId 传 null 表示解绑 */
    @Transactional
    public void updateVehicle(Long id, Long vehicleId) {
        Driver driver = getById(id);
        checkVehicleBelongsToCompany(vehicleId, driver.getCompanyId());
        driverMapper.updateVehicleId(id, vehicleId);
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        driverMapper.deleteById(id);
        log.info("删除司机 id={}", id);
    }

    private void checkEmployeeNoUnique(Long companyId, String employeeNo, Long excludeId) {
        if (driverMapper.countByEmployeeNo(companyId, employeeNo, excludeId) > 0) {
            throw new BusinessException("该车队下工号已存在");
        }
    }

    /** 只能绑定本车队的车辆 */
    private void checkVehicleBelongsToCompany(Long vehicleId, Long companyId) {
        if (vehicleId == null) {
            return;
        }
        Vehicle vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            throw new BusinessException("绑定的车辆不存在");
        }
        if (!companyId.equals(vehicle.getCompanyId())) {
            throw new BusinessException("不能绑定其他车队的车辆");
        }
    }
}
