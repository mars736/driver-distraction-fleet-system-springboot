package service.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.constant.CommonConstant;
import common.exception.BusinessException;
import dto.operations.DifyAgent.*;
import dto.operations.TransportOrders.Draft;
import dto.operations.Workforce.ShiftRequest;
import entity.Fleet;
import lombok.RequiredArgsConstructor;
import mapper.DifyAgentMapper;
import mapper.FleetMapper;
import mapper.OperationsRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static service.operations.OperationsAccess.*;

@Service
@RequiredArgsConstructor
public class DifyDraftService {
    private final DifyAgentMapper mapper;
    private final FleetMapper fleets;
    private final OperationsRecordMapper records;
    private final DifyShiftPlanner planner;
    private final WorkforceService workforce;
    private final TransportOrderService orders;
    private final ObjectMapper json;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview<Draft> previewOrder(Draft request) {
        checkFleet(fleets.selectById(request.fleetId()));
        return save(Action.ORDER, orders.preview(request));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Preview<SchedulePlan> previewShifts(ScheduleRequest request) {
        checkFleet(fleets.selectById(request.fleetId()));
        var plan = planner.plan(request, now());
        if (plan.proposed().isEmpty()) { return new Preview<>(null, null, plan); }
        return save(Action.SHIFTS, plan);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Confirmation confirm(String id) {
        var draft = mapper.lockDraft(id, admin().userId());
        if (draft == null) { throw new BusinessException(404, "草稿不存在或已清理"); }
        if (draft.result() != null) {
            return new Confirmation(id, draft.action(), decode(draft.result(), JsonNode.class));
        }
        check(now().isBefore(draft.expiresAt()), 409, "草稿已过期，请重新生成");
        Object result = switch (draft.action()) {
            case ORDER -> createOrder(decode(draft.payload(), Draft.class));
            case SHIFTS -> applyShifts(decode(draft.payload(), SchedulePlan.class));
        };
        if (mapper.completeDraft(id, encode(result)) != 1) {
            throw new IllegalStateException("Agent 确认回执写入失败");
        }
        return new Confirmation(id, draft.action(), result);
    }

    private Object createOrder(Draft draft) {
        checkFleet(fleets.lockById(draft.fleetId()));
        var order = orders.create(draft);
        return Map.of("orderId", order.id(), "orderNo", order.orderNo(), "status", order.status(), "version", order.version());
    }

    private ScheduleApplied applyShifts(SchedulePlan plan) {
        checkFleet(fleets.lockById(plan.fleetId()));
        var shifts = new ArrayList<Long>();
        LocalDateTime at = now();
        var sorted = plan.proposed().stream().sorted(Comparator.comparingLong(ProposedShift::driverId)
                .thenComparing(ProposedShift::date)).toList();
        for (var proposal : sorted) {
            var driver = records.lockUser(proposal.driverId());
            check(driver != null && CommonConstant.ROLE_DRIVER.equals(driver.getRole())
                    && Boolean.TRUE.equals(driver.getEnabled()) && Long.valueOf(plan.fleetId()).equals(driver.getFleetId()),
                    409, "司机档案已变更，请重新预排班");
            check(!proposal.startsAt().isBefore(at), 409, "班次已开始，请重新预排班");
            var input = new ShiftRequest(proposal.driverId(), proposal.date(), proposal.type(),
                    proposal.startsAt(), proposal.endsAt(), 0L);
            shifts.add(workforce.saveShift(input).id());
        }
        return new ScheduleApplied(plan.fleetId(), List.copyOf(shifts));
    }

    private <T> Preview<T> save(Action action, T payload) {
        var at = now().truncatedTo(ChronoUnit.MILLIS);
        mapper.purgeOldDrafts(at.minusDays(1));
        var id = UUID.randomUUID().toString();
        var expiresAt = at.plusMinutes(30);
        if (mapper.saveDraft(new DraftRow(id, admin().userId(), action, encode(payload), expiresAt, null)) != 1) {
            throw new IllegalStateException("Agent 草稿写入失败");
        }
        return new Preview<>(id, expiresAt, payload);
    }

    private static void checkFleet(Fleet fleet) {
        check(fleet != null, 404, "车队不存在");
        check(Boolean.TRUE.equals(fleet.getEnabled()), 409, "车队已停运");
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存 Agent 草稿或回执", exception);
        }
    }

    private <T> T decode(String payload, Class<T> type) {
        try {
            return json.readValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取 Agent 草稿或回执", exception);
        }
    }
}
