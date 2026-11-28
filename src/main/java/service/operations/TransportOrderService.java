package service.operations;

import common.exception.BusinessException;
import common.response.PageResult;
import dto.operations.TransportOrders.*;
import dto.operations.Workforce.TaskRequest;
import lombok.RequiredArgsConstructor;
import mapper.DriverSafetyRatingMapper;
import mapper.FleetMapper;
import mapper.OperationsRecordMapper;
import mapper.TransportOrderMapper;
import mapper.WorkforceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.audit.OperationAction;
import service.audit.OperationLogService;
import service.rating.DriverSafetyRating.Level;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static dto.operations.TransportOrders.ConflictReason.*;
import static service.operations.OperationsAccess.*;

@Service
@RequiredArgsConstructor
public class TransportOrderService {
    private final TransportOrderMapper orders;
    private final WorkforceMapper tasks;
    private final WorkforceService workforce;
    private final FleetMapper fleets;
    private final OperationsRecordMapper records;
    private final OperationLogService audit;
    private final DriverSafetyRatingMapper ratings;

    @Value("${transport-orders.high-minimum-rating:A}")
    private Level highMinimumRating;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderRow create(Draft input) {
        long actor = admin().userId();
        var at = now();
        var draft = normalize(input, at);
        check(fleets.lockById(draft.fleetId()) != null, 404, "车队不存在");
        String day = at.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        String number = "TO-" + day + "-" + UUID.randomUUID();
        orders.create(draft, number, actor, at);
        long id = records.generatedId();
        audit.success(OperationAction.TRANSPORT_ORDER_CREATE, id, draft.fleetId());
        return load(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<OrderRow> list(Long fleetId, Status status, Requirement requirement, int page, int size) {
        admin();
        check(fleetId == null || fleetId > 0, 400, "车队 ID 必须为正数");
        var rows = orders.list(fleetId, status, requirement, offset(page, size), size);
        return PageResult.of(rows, orders.count(fleetId, status, requirement), page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TransportOrderDetail detail(long id) {
        admin();
        var order = load(id);
        var snapshots = orders.dispatchRatings(id).stream()
                .collect(Collectors.toMap(DispatchRatingRow::taskId, DispatchRatingRow::rating));
        var history = tasks.orderedTasks(id).stream()
                .map(task -> new AssignmentHistory(task, snapshots.get(task.id()))).toList();
        return new TransportOrderDetail(order, history);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderRow update(long id, Edit input) {
        admin();
        var order = lock(id);
        requireWaiting(order, input.expectedVersion());
        var at = now();
        var draft = normalize(input.draft(), at);
        check(fleets.lockById(draft.fleetId()) != null, 404, "车队不存在");
        checkUpdated(orders.update(id, draft, order.version(), at));
        audit.success(OperationAction.TRANSPORT_ORDER_UPDATE, id, draft.fleetId());
        return load(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<TransportOrderCandidate> candidates(long id, int page, int size) {
        admin();
        var order = load(id);
        requireWaiting(order);
        var rows = orders.candidates(order, now(), offset(page, size), size, acceptedLevels()).stream()
                .map(TransportOrderCandidate::new).toList();
        return PageResult.of(rows, orders.countCandidates(order.fleetId()), page, size);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DispatchReceipt assign(long id, TransportOrderAssignment input, String requestKey) {
        admin();
        check(requestKey != null, 400, "需要提供派发请求标识");
        WorkforceService.validateRequestKey(requestKey);
        var order = lock(id);
        var previous = orders.replay(id, requestKey);
        if (previous != null) {
            if (previous.driverId() != input.driverId() || previous.vehicleId() != input.vehicleId()
                    || previous.expectedVersion() != input.expectedVersion()) {
                throw new TransportOrderConflict(ORDER_REQUEST_KEY_REUSED, "请求标识已用于不同派发条件");
            }
            return new DispatchReceipt(id, previous.taskId(), Status.ASSIGNED, previous.expectedVersion() + 1);
        }
        requireWaiting(order, input.expectedVersion());
        var taskRequest = new TaskRequest(input.driverId(), input.vehicleId(), order.taskType(), order.description(),
                order.startsAt(), order.endsAt());
        var link = new TaskLink(id, order.fleetId(), order.originAddress(), order.destinationAddress(),
                order.requirement(), requestKey, order.version());
        var task = workforce.createOrderTask(taskRequest, link);
        if (order.requirement() == Requirement.HIGH) { saveRatingForDispatch(task.id(), task.driverId()); }
        checkUpdated(orders.assign(id, task.id(), order.version(), now()));
        audit.success(OperationAction.TRANSPORT_ORDER_ASSIGN, id, order.fleetId(), Long.toString(task.id()));
        return new DispatchReceipt(id, task.id(), Status.ASSIGNED, order.version() + 1);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderRow cancel(long id, Cancel input) {
        admin();
        var order = lock(id);
        requireWaiting(order, input.expectedVersion());
        checkUpdated(orders.cancel(id, order.version(), now()));
        audit.success(OperationAction.TRANSPORT_ORDER_CANCEL, id, order.fleetId());
        return load(id);
    }

    private List<Level> acceptedLevels() {
        return Arrays.stream(Level.values()).filter(level -> level.compareTo(highMinimumRating) <= 0).toList();
    }

    private void saveRatingForDispatch(long taskId, long driverId) {
        // 与评级发布串行化，直到任务和评级快照一同提交。
        ratings.lockPublicationForShare();
        var current = ratings.currentRatings(List.of(driverId));
        if (current.isEmpty()) {
            throw new TransportOrderConflict(RATING_UNAVAILABLE, "高要求任务需要已发布的司机长期评级");
        }
        var rating = current.getFirst().view();
        if (!acceptedLevels().contains(rating.level())) {
            throw new TransportOrderConflict(RATING_BELOW_REQUIREMENT,
                    "司机长期评级低于订单要求，最低为 " + highMinimumRating + " 级");
        }
        if (orders.saveDispatchRating(taskId, rating) != 1) {
            throw new IllegalStateException("派发评级快照保存失败");
        }
    }

    private OrderRow lock(long id) {
        check(id > 0, 400, "订单 ID 必须为正数");
        check(orders.lock(id) != null, 404, "运输订单不存在");
        return load(id);
    }

    private OrderRow load(long id) {
        check(id > 0, 400, "订单 ID 必须为正数");
        var order = orders.detail(id);
        if (order == null) { throw new BusinessException(404, "运输订单不存在"); }
        return order;
    }

    private static Draft normalize(Draft input, LocalDateTime at) {
        var starts = input.startsAt().truncatedTo(ChronoUnit.MILLIS);
        var ends = input.endsAt().truncatedTo(ChronoUnit.MILLIS);
        window(starts, ends, 366);
        check(ends.isAfter(at), 400, "订单结束时间应晚于当前时间");
        return new Draft(input.fleetId(), input.taskType().strip(), input.description().strip(),
                input.originAddress().strip(), input.destinationAddress().strip(), starts, ends,
                input.requirement() == null ? Requirement.NORMAL : input.requirement());
    }

    private static void requireWaiting(OrderRow order, long version) {
        if (order.version() != version) {
            throw new TransportOrderConflict(ORDER_VERSION_CONFLICT, "订单已变化，请刷新后再操作");
        }
        requireWaiting(order);
    }

    private static void requireWaiting(OrderRow order) {
        if (order.status() != Status.WAITING_DISPATCH) {
            throw new TransportOrderConflict(ORDER_NOT_DISPATCHABLE, "订单当前不在待派单状态");
        }
    }

    private static void checkUpdated(int changed) {
        if (changed != 1) {
            throw new TransportOrderConflict(ORDER_VERSION_CONFLICT, "订单已变化，请刷新后再操作");
        }
    }
}
