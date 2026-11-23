package service.operations;

import common.constant.CommonConstant;
import common.util.GeoDistance;
import dto.operations.ViolationDispatch.*;
import dto.operations.Workforce.Position;
import lombok.RequiredArgsConstructor;
import mapper.AlertReviewMapper;
import mapper.ViolationDispatchMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import service.alert.live.AlertCreatedEvent;
import service.alert.live.AlertRevokedEvent;
import service.audit.OperationAction;
import service.audit.OperationLogService;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static service.operations.OperationsAccess.*;

@Service
@RequiredArgsConstructor
public class ViolationDispatchService {
    private static final List<PersonnelAction> ACTIONS = List.of(
            PersonnelAction.TRAINING, PersonnelAction.CHANGE_SHIFT, PersonnelAction.STOP_SHIFT);

    private final ViolationDispatchMapper orders;
    private final AlertReviewMapper alerts;
    private final OperationLogService audit;

    @Value("${operations.dispatch.high-risk-window-days:30}") private int highRiskWindowDays;
    @Value("${operations.dispatch.high-risk-threshold:3}") private int highRiskThreshold;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAlertCreated(AlertCreatedEvent event) {
        if (CommonConstant.RECORD_TYPE_SMS_ALERT.equals(event.recordType())
                || CommonConstant.RECORD_TYPE_BACKLOG.equals(event.recordType())) {
            orders.createFromAlert(event.id());
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAlertRevoked(AlertRevokedEvent event) {
        if (orders.cancelForAlert(event.alertId()) == 1) {
            audit.success(OperationAction.DISPATCH_ALERT_REVOKED, event.alertId(), event.fleetId());
        }
    }

    public List<WorkOrder> list(Long fleetId, Long driverId, Status status, RiskLevel risk, int page, int size) {
        admin();
        check((fleetId == null || fleetId > 0) && (driverId == null || driverId > 0), 400, "车队或司机 ID 无效");
        return orders.list(fleetId, driverId, status, risk, offset(page, size), size);
    }

    public WorkOrder detail(long id) {
        admin();
        return find(id);
    }

    @Transactional
    public void reportPosition(Position position) {
        long id = admin().userId();
        check(Double.isFinite(position.latitude()) && Double.isFinite(position.longitude()), 400, "坐标无效");
        orders.reportPosition(id, position, now());
    }

    public List<Candidate> candidates(long id) {
        admin();
        return ranked(find(id), now());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkOrder assign(long id, long assigneeId) {
        admin();
        return assignTo(lockActiveOrder(id, Status.PENDING), assigneeId, "MANUAL");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkOrder assignNearest(long id) {
        admin();
        var order = lockActiveOrder(id, Status.PENDING);
        var candidates = ranked(order, now());
        check(!candidates.isEmpty() && candidates.getFirst().dispatchCost() != null, 409, "缺少工单位置或人员近期位置，请手动派发");
        return assignTo(order, candidates.getFirst().userId(), "NEAREST");
    }

    private WorkOrder assignTo(WorkOrder order, long assigneeId, String method) {
        check(orders.eligibleOperator(assigneeId), 400, "处置人应为启用的平台管理员");
        check(orders.assign(order.id(), assigneeId, now()) == 1, 409, "工单已派发或已取消");
        audit.success(OperationAction.DISPATCH_ASSIGN, order.id(), order.fleetId(), method);
        return find(order.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkOrder start(long id) {
        admin();
        var order = lockActiveOrder(id, Status.ASSIGNED);
        check(orders.start(id, now()) == 1, 409, "工单状态已变更");
        audit.success(OperationAction.DISPATCH_START, id, order.fleetId());
        return find(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkOrder close(long id, Close input) {
        long actor = admin().userId();
        var order = lockActiveOrder(id, Status.IN_PROGRESS);
        check(orders.close(id, actor, input, now()) == 1, 409, "工单状态已变更");
        audit.success(OperationAction.DISPATCH_CLOSE, id, order.fleetId(), input.outcome().name());
        return find(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Recommendation recommendation(long id) {
        admin();
        return evaluate(find(id), now());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Recommendation decidePersonnel(long id, PersonnelDecision input) {
        long actor = admin().userId();
        var order = lockActiveOrder(id, Status.CLOSED);
        check(order.recommendationAction() == null, 409, "人员建议已处理");
        var at = now();
        check(evaluate(order, at).eligible(), 409, "工单当前未达到人员建议条件");
        check(orders.decidePersonnel(id, actor, input, at) == 1, 409, "人员建议已处理");
        audit.success(OperationAction.DISPATCH_PERSONNEL, id, order.fleetId(), input.action().name());
        return evaluate(find(id), at);
    }

    private WorkOrder lockActiveOrder(long id, Status expected) {
        Long alertId = orders.alertId(id);
        check(alertId != null, 404, "工单不存在");
        // 复用告警锁，派发、关闭与复核撤销按同一顺序串行。
        var alert = alerts.lockAlert(alertId);
        check(alert != null, 404, "关联告警不存在");
        check(!"REVOKED".equals(alert.getStatus()), 409, "关联告警已撤销");
        WorkOrder order = find(id);
        check(order.status() == expected, 409, "当前工单状态不允许该操作");
        return order;
    }

    private WorkOrder find(long id) {
        var order = orders.detail(id);
        check(order != null, 404, "工单不存在");
        return order;
    }

    private List<Candidate> ranked(WorkOrder order, LocalDateTime now) {
        LocalDateTime earliest = now.minusMinutes(10);
        return orders.operators().stream()
                .map(operator -> candidate(order, operator, earliest))
                .sorted(Comparator.comparing(Candidate::dispatchCost, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(Candidate::activeOrders).thenComparingLong(Candidate::userId))
                .toList();
    }

    private Candidate candidate(WorkOrder order, Operator operator, LocalDateTime earliest) {
        Double distance = null;
        if (order.latitude() != null && order.longitude() != null && operator.reportedAt() != null
                && !operator.reportedAt().isBefore(earliest)) {
            distance = GeoDistance.kilometers(order.latitude(), order.longitude(), operator.latitude(), operator.longitude());
        }
        Double cost = distance == null ? null : distance + 2.0 * operator.activeOrders();
        return new Candidate(operator.userId(), operator.name(), operator.activeOrders(), distance, cost);
    }

    private Recommendation evaluate(WorkOrder order, LocalDateTime now) {
        var from = now.minusDays(highRiskWindowDays);
        boolean relevant = order.status() == Status.CLOSED && order.riskLevel() == RiskLevel.HIGH
                && "CONFIRMED".equals(order.alertStatus()) && order.driverId() != null
                && !order.capturedAt().isBefore(from) && !order.capturedAt().isAfter(now);
        long count = relevant ? orders.confirmedHighRisk(order.driverId(), from, now) : 0;
        boolean eligible = relevant && count >= highRiskThreshold;
        return new Recommendation(order.id(), order.driverId(), highRiskWindowDays, highRiskThreshold, count, eligible,
                eligible ? ACTIONS : List.of(), order.recommendationAction(), order.recommendationNote(),
                order.recommendationBy(), order.recommendationAt());
    }
}
