package service.audit;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.security.LoginUser;
import common.security.UserContext;
import entity.OperationLog;
import entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.OperationLogMapper;
import mapper.VehicleMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String TRACE_ATTRIBUTE = OperationLogService.class.getName() + ".requestId";

    public enum LoginFailure { ACCOUNT_NOT_FOUND, ACCOUNT_DISABLED, BAD_PASSWORD }

    private final OperationLogMapper mapper;
    private final VehicleMapper vehicles;

    public void success(OperationAction action, Long targetId, Long fleetId) {
        success(action, targetId, fleetId, null);
    }

    public void success(OperationAction action, Long targetId, Long fleetId, String detailCode) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Long actorId = auth != null && auth.getPrincipal() instanceof LoginUser actor ? actor.userId() : null;
        record(action, actorId, targetId, fleetId, true, detailCode);
    }

    public void cameraChange(OperationAction action, Long targetId, Long vehicleId) {
        var vehicle = vehicleId == null ? null : vehicles.selectById(vehicleId);
        success(action, targetId, vehicle == null ? null : vehicle.getFleetId());
    }

    public void authSuccess(OperationAction action, User user) {
        record(action, user.getId(), user.getId(), user.getFleetId(), true, null);
    }

    public void loginFailure(User user, LoginFailure failure) {
        try {
            Long id = user == null ? null : user.getId();
            record(OperationAction.AUTH_LOGIN, id, id, user == null ? null : user.getFleetId(), false, failure.name());
        } catch (DataAccessException ex) {
            log.warn("登录失败审计写入异常，异常类型={}", ex.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<OperationLog> list(Long fleetId, Long actorId, OperationAction action, Long targetId,
                                        LocalDateTime startTime, LocalDateTime endTime, int page, int pageSize) {
        if (!CommonConstant.ROLE_PLATFORM_ADMIN.equals(UserContext.get().role())) {
            throw new BusinessException(403, "仅管理员可查看操作日志");
        }
        if ((fleetId != null && fleetId < 1) || (actorId != null && actorId < 1)
                || (targetId != null && targetId < 1) || page < 1 || pageSize < 1 || pageSize > 100
                || (startTime != null && endTime != null && !startTime.isBefore(endTime))) {
            throw new BusinessException(400, "操作日志查询条件无效");
        }
        String code = action == null ? null : action.name();
        long total = mapper.count(fleetId, actorId, code, targetId, startTime, endTime);
        var rows = mapper.list(fleetId, actorId, code, targetId, startTime, endTime, (page - 1L) * pageSize, pageSize);
        return PageResult.of(rows, total, page, pageSize);
    }

    private void record(OperationAction action, Long actorId, Long targetId, Long fleetId,
                        boolean success, String detailCode) {
        var row = new OperationLog();
        row.setAction(action.name());
        row.setTargetType(action.targetType());
        row.setActorId(actorId);
        row.setTargetId(targetId);
        row.setFleetId(fleetId);
        row.setOutcome(success ? "SUCCESS" : "FAILURE");
        row.setDetailCode(detailCode);
        row.setRequestId(requestId());
        row.setOperatedAt(LocalDateTime.now(ZONE));
        mapper.insert(row);
    }

    private String requestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) { return UUID.randomUUID().toString(); }
        String id = (String) attributes.getAttribute(TRACE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (id == null) {
            id = UUID.randomUUID().toString();
            attributes.setAttribute(TRACE_ATTRIBUTE, id, RequestAttributes.SCOPE_REQUEST);
        }
        return id;
    }
}
