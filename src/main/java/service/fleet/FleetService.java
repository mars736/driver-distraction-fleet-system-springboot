package service.fleet;

import common.exception.BusinessException;
import common.response.PageResult;
import entity.Fleet;
import lombok.RequiredArgsConstructor;
import service.audit.OperationAction;
import service.audit.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import mapper.FleetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 车队档案业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FleetService {

    private final FleetMapper fleetMapper;
    private final OperationLogService audit;

    public PageResult<Fleet> list(String keyword, Integer page, Integer pageSize) {
        int currentPage = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 100);
        int offset = (currentPage - 1) * size;

        List<Fleet> list = fleetMapper.list(keyword, offset, size);
        long total = fleetMapper.count(keyword);
        return PageResult.of(list, total, currentPage, size);
    }

    public Fleet getById(Long id) {
        Fleet fleet = fleetMapper.selectById(id);
        if (fleet == null) {
            throw new BusinessException(404, "车队不存在");
        }
        return fleet;
    }

    @Transactional
    public Fleet create(Fleet fleet) {
        if (fleetMapper.countByFleetNo(fleet.getFleetNo(), null) > 0) {
            throw new BusinessException("车队编号已存在");
        }
        if (fleet.getEnabled() == null) {
            fleet.setEnabled(true);
        }
        fleetMapper.insert(fleet);
        audit.success(OperationAction.FLEET_CREATE, fleet.getId(), fleet.getId());
        log.info("新增车队 id={}, fleetNo={}", fleet.getId(), fleet.getFleetNo());
        return fleet;
    }

    @Transactional
    public Fleet update(Fleet fleet) {
        getById(fleet.getId());
        if (fleetMapper.countByFleetNo(fleet.getFleetNo(), fleet.getId()) > 0) {
            throw new BusinessException("车队编号已存在");
        }
        fleetMapper.update(fleet);
        audit.success(OperationAction.FLEET_UPDATE, fleet.getId(), fleet.getId());
        return fleet;
    }

    @Transactional
    public void updateEnabled(Long id, Boolean enabled) {
        getById(id);
        fleetMapper.updateEnabled(id, enabled);
        audit.success(OperationAction.FLEET_ENABLED, id, id, Boolean.TRUE.equals(enabled) ? "ENABLED" : "DISABLED");
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        fleetMapper.deleteById(id);
        audit.success(OperationAction.FLEET_DELETE, id, id);
        log.info("删除车队 id={}", id);
    }
}
