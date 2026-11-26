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
        if (orders.applyRevocation(event) == 1) {
            audit.success(OperationAction.DISPATCH_ALERT_REVOKED, event.alertId(), event.fleetId(), event.source().name());
        }
    }

    public List<WorkOrder> list(OrderFilter filter, int page, int size) {
        admin();
        return orders.list(filter, null, offset(page, size), size);
    }

    public OrderPage page(OrderFilter filter, Long beforeId, int size) {
        admin();
        check(beforeId == null || beforeId > 0, 400, "分页游标必须为正数");
        var rows = orders.list(filter, beforeId, offset(1, size), size + 1);
        boolean hasNext = rows.size() > size;
        var records = hasNext ? List.copyOf(rows.subList(0, size)) : rows;
        return new OrderPage(records, hasNext, hasNext ? records.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkOrderDetail detail(long id) {
        admin();
        var order = find(id);
        boolean legacy = isLegacyRevocation(order);
        var note = legacy ? orders.legacyRevocationNote(id) : null;
        boolean needsReview = legacy && note == null;
        return new WorkOrderDetail(order, availableActions(order, needsReview), needsReview, note);
    }

    private List<OrderAction> availableActions(WorkOrder order, boolean needsReview) {
        if ("REVOKED".equals(order.alertStatus())) {
            if (needsReview) { return List.of(OrderAction.RECORD_LEGACY_REVOCATION); }
            if (order.status() == Status.REVOKE_WRAPUP) { return List.of(OrderAction.REVOCATION_WRAPUP); }
            return List.of();
        }
        return switch (order.status()) {
            case PENDING -> {
                var candidates = order.latitude() == null || order.longitude() == null
                        ? List.<Candidate>of() : ranked(order, now());
                yield canAssignNearest(candidates) ? List.of(OrderAction.ASSIGN, OrderAction.ASSIGN_NEAREST)
                        : List.of(OrderAction.ASSIGN);
            }
            case ASSIGNED -> List.of(OrderAction.START);
            case IN_PROGRESS -> List.of(OrderAction.CLOSE);
            case CLOSED -> order.recommendationAction() == null && evaluate(order, now()).eligible()
                    ? List.of(OrderAction.PERSONNEL_DECISION) : List.of();
            default -> List.of();
        };
    }

    private boolean isLegacyRevocation(WorkOrder order) {
        return order.status() == Status.CANCELED && "REVOKED".equals(order.alertStatus())
                && order.startedAt() != null && order.closedAt() == null && order.revocationSource() == null;
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
        check(canAssignNearest(candidates), 409, "缺少工单位置或人员近期位置，请手动派发");
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
        check(input.outcome() != Outcome.REVOKED_WRAPUP, 400, "撤销收尾须通过专用入口");
        check(orders.close(id, actor, input, now()) == 1, 409, "工单状态已变更");
        audit.success(OperationAction.DISPATCH_CLOSE, id, order.fleetId(), input.outcome().name());
        return find(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkOrder wrapUpRevocation(long id, RevocationWrapup input) {
        long actor = admin().userId();
        var order = lockOrder(id, Status.REVOKE_WRAPUP, true);
        var notes = normalizeNotes(input);
        check(orders.closeRevoked(id, actor, notes, now()) == 1, 409, "工单状态已变更");
        audit.success(OperationAction.DISPATCH_REVOCATION_WRAPUP, id, order.fleetId());
        return find(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LegacyRevocationNote recordLegacyRevocation(long id, RevocationWrapup input) {
        long actor = admin().userId();
        var order = lockOrder(id, Status.CANCELED, true);
        check(isLegacyRevocation(order), 409, "工单无需历史撤销补记");
        check(orders.legacyRevocationNote(id) == null, 409, "工单已有历史撤销补记");
        var notes = normalizeNotes(input);
        var note = new LegacyRevocationNote(id, notes.workDone(), notes.resolution(), actor, now());
        check(orders.insertLegacyRevocationNote(note) == 1, 409, "历史撤销补记未写入");
        audit.success(OperationAction.DISPATCH_LEGACY_REVOCATION, id, order.fleetId());
        return orders.legacyRevocationNote(id);
    }

    private RevocationWrapup normalizeNotes(RevocationWrapup input) {
        check(!input.workDone().isBlank() && !input.resolution().isBlank(), 400, "已完成工作和收尾说明不能为空");
        return new RevocationWrapup(input.workDone().strip(), input.resolution().strip());
    }

    private boolean canAssignNearest(List<Candidate> candidates) {
        return !candidates.isEmpty() && candidates.getFirst().dispatchCost() != null;
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
        return lockOrder(id, expected, false);
    }

    private WorkOrder lockOrder(long id, Status expected, boolean revoked) {
        Long alertId = orders.alertId(id);
        check(alertId != null, 404, "工单不存在");
        // 复用告警锁，派发、关闭与复核撤销按同一顺序串行。
        var alert = alerts.lockAlert(alertId);
        check(alert != null, 404, "关联告警不存在");
        check("REVOKED".equals(alert.getStatus()) == revoked, 409,
                revoked ? "关联告警尚未撤销" : "关联告警已撤销");
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
