package service.score;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.security.UserContext;
import dto.score.ScoreQueryRequest;
import lombok.RequiredArgsConstructor;
import mapper.DriverScoreMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import vo.score.*;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DriverScoreService {
    private final DriverScoreMapper mapper;

    public DriverScoreView mine(ScoreQueryRequest request) {
        var login = UserContext.get();
        if (!CommonConstant.ROLE_DRIVER.equals(login.role())) {
            throw new BusinessException(403, "仅司机可查询本人评分，请管理员使用司机 ID 查询");
        }
        return summary(login.userId(), request);
    }

    public DriverScoreView summary(long driverId, ScoreQueryRequest request) {
        authorize(driverId);
        var period = period(request);
        var result = mapper.summary(driverId, period);
        if (result == null) { throw new BusinessException(404, "司机不存在"); }
        decorate(result, period);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<ScoreRecordView> records(long driverId, ScoreQueryRequest request) {
        authorize(driverId);
        var period = period(request);
        // 明细和总条数在同一只读事务内查询，避免分页期间撤销/新增造成两份结果不一致。
        if (!mapper.driverExists(driverId)) { throw new BusinessException(404, "司机不存在"); }
        return PageResult.of(mapper.records(driverId, period, offset(request), request.getPageSize()),
                mapper.countRecords(driverId, period), request.getPage(), request.getPageSize());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<DriverScoreView> ranking(ScoreQueryRequest request) {
        if (!CommonConstant.ROLE_PLATFORM_ADMIN.equals(UserContext.get().role())) {
            throw new BusinessException(403, "仅管理员可查看司机排名");
        }
        var period = period(request);
        var rows = mapper.ranking(request.getFleetId(), period, offset(request), request.getPageSize());
        rows.forEach(row -> decorate(row, period));
        return PageResult.of(rows, mapper.countDrivers(request.getFleetId()), request.getPage(), request.getPageSize());
    }

    @Transactional(readOnly = true)
    public List<DriverScoreView> exportRows(ScoreQueryRequest request, Long driverId) {
        if (!CommonConstant.ROLE_PLATFORM_ADMIN.equals(UserContext.get().role())) {
            throw new BusinessException(403, "仅管理员可导出司机评分");
        }
        var period = period(request);
        if (driverId != null) {
            var row = mapper.summary(driverId, period);
            if (row == null || (request.getFleetId() != null && !Objects.equals(request.getFleetId(), row.getFleetId()))) {
                throw new BusinessException(404, "所选车队中没有该司机");
            }
            decorate(row, period);
            return List.of(row);
        }
        var rows = mapper.ranking(request.getFleetId(), period, 0, 10_001);
        if (rows.size() > 10_000) { throw new BusinessException(422, "司机评分超过 10000 条，请按车队筛选"); }
        rows.forEach(row -> decorate(row, period));
        return rows;
    }

    private void authorize(long driverId) {
        if (driverId <= 0) { throw new BusinessException(400, "司机 ID 必须为正数"); }
        var user = UserContext.get();
        if (CommonConstant.ROLE_PLATFORM_ADMIN.equals(user.role())) { return; }
        if (!CommonConstant.ROLE_DRIVER.equals(user.role())) { throw new BusinessException(403, "无权限查看评分"); }
        // 他人和不存在统一返回 404，避免通过评分接口探测其他司机的信息。
        if (!Long.valueOf(driverId).equals(user.userId())) { throw new BusinessException(404, "司机不存在"); }
    }

    private ScorePeriod period(ScoreQueryRequest request) {
        return ScorePeriod.parse(request.getMonth(), Clock.system(ScorePeriod.ZONE));
    }
    private long offset(ScoreQueryRequest request) { return ((long) request.getPage() - 1) * request.getPageSize(); }
    private void decorate(DriverScoreView row, ScorePeriod period) {
        row.setMonth(period.month());
        var level = CommonConstant.ScoreLevel.fromScore(row.getScore());
        row.setLevel(level.name()); row.setLevelLabel(level.getLabel());
    }
}
