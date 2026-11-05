package service.vehicle;

import common.exception.BusinessException;
import common.response.PageResult;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.CompanyMapper;
import mapper.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 车辆业务（M0）。
 * 依赖 CompanyMapper 做"所属车队必须存在"的校验，避免产生悬空 company_id。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleMapper vehicleMapper;
    private final CompanyMapper companyMapper;

    public PageResult<Vehicle> list(Long companyId, Integer page, Integer pageSize) {
        int currentPage = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 100);
        int offset = (currentPage - 1) * size;

        List<Vehicle> list = vehicleMapper.list(companyId, offset, size);
        long total = vehicleMapper.count(companyId);
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
        checkCompanyExists(vehicle.getCompanyId());
        if (vehicleMapper.countByPlateNo(vehicle.getPlateNo(), null) > 0) {
            throw new BusinessException("车牌号已存在");
        }
        if (vehicle.getMonitorEnabled() == null) {
            vehicle.setMonitorEnabled(1);
        }
        vehicleMapper.insert(vehicle);
        log.info("新增车辆 id={}, plateNo={}", vehicle.getId(), vehicle.getPlateNo());
        return vehicle;
    }

    @Transactional
    public Vehicle update(Vehicle vehicle) {
        getById(vehicle.getId());
        checkCompanyExists(vehicle.getCompanyId());
        if (vehicleMapper.countByPlateNo(vehicle.getPlateNo(), vehicle.getId()) > 0) {
            throw new BusinessException("车牌号已存在");
        }
        vehicleMapper.update(vehicle);
        return vehicle;
    }

    @Transactional
    public void updateMonitorEnabled(Long id, Integer monitorEnabled) {
        getById(id);
        vehicleMapper.updateMonitorEnabled(id, monitorEnabled);
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        vehicleMapper.deleteById(id);
        log.info("删除车辆 id={}", id);
    }

    private void checkCompanyExists(Long companyId) {
        if (companyMapper.selectById(companyId) == null) {
            throw new BusinessException("所属车队不存在");
        }
    }
}
