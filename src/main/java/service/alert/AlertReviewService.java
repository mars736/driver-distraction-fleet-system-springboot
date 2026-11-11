package service.alert;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.security.UserContext;
import dto.alert.AlertReviewRequest;
import entity.AlertReviewRecord;
import lombok.RequiredArgsConstructor;
import mapper.AlertReviewMapper;
import mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AlertReviewService {
    private final AlertReviewMapper mapper;
    private final UserMapper userMapper;
    private final AlertQueryService queryService;

    @Transactional
    public AlertReviewRecord review(long id, AlertReviewRequest request) {
        var login = UserContext.get();
        if (!CommonConstant.ROLE_PLATFORM_ADMIN.equals(login.role())) {
            throw new BusinessException(403, "仅管理员可处理告警");
        }
        if (id <= 0) { throw new BusinessException(400, "告警 ID 必须为正数"); }
        if (request.getStatus() != AlertStatus.CONFIRMED && request.getStatus() != AlertStatus.REVOKED) {
            throw new BusinessException(400, "处理状态只能为 CONFIRMED 或 REVOKED");
        }
        // Java strip/isBlank 处理 Unicode 空白，不能让全空格原因进入审计记录。
        String reason = request.getReason() == null ? "" : request.getReason().strip();
        if (reason.isBlank() || reason.length() > 500) {
            throw new BusinessException(400, "处理原因应为 1—500 个字符");
        }
        var operator = userMapper.selectById(login.userId());
        if (operator == null || !Boolean.TRUE.equals(operator.getEnabled())
                || !CommonConstant.ROLE_PLATFORM_ADMIN.equals(operator.getRole())) {
            throw new BusinessException(403, "管理员账号不可用");
        }
        // 行锁串行化同一告警的并发处理；状态和操作历史必须在一个事务中提交。
        var alert = mapper.lockAlert(id);
        if (alert == null) { throw new BusinessException(404, "告警不存在"); }
        if (!AlertStatus.OPEN.name().equals(alert.getStatus())) {
            throw new BusinessException(409, "告警已处理，请刷新后查看结果");
        }
        if (mapper.updateOpenStatus(id, request.getStatus().name()) != 1) {
            throw new BusinessException(409, "告警状态已变更，请刷新后重试");
        }
        var record = new AlertReviewRecord();
        record.setAlertId(id);
        record.setFromStatus(AlertStatus.OPEN.name());
        record.setToStatus(request.getStatus().name());
        record.setReason(reason);
        record.setOperatorId(operator.getId());
        record.setOperatorUsername(operator.getUsername());
        record.setOperatorName(operator.getName());
        record.setOperatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS));
        if (mapper.insertReview(record) != 1) { throw new IllegalStateException("告警处理记录保存失败"); }
        return record;
    }

    @Transactional(readOnly = true)
    public List<AlertReviewRecord> history(long id) {
        // 复用告警详情的归属校验：司机不能通过历史入口查看别人的告警。
        queryService.detail(id);
        return mapper.listReviews(id);
    }
}
