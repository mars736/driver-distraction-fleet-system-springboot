package service.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.operations.FleetCare.*;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import mapper.FleetCareMapper;
import mapper.OperationsRecordMapper;
import mapper.VehicleMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.audit.OperationAction;
import service.audit.OperationLogService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static service.operations.OperationsAccess.*;

@Service
@RequiredArgsConstructor
public class FleetCareService {
    private static final BigDecimal DB_AMOUNT_LIMIT = new BigDecimal("9999999999.99");

    private final FleetCareMapper mapper;
    private final OperationsRecordMapper records;
    private final VehicleMapper vehicles;
    private final OperationLogService audit;
    private final ObjectMapper json;

    @Value("${operations.fuel.deviation:0.25}") private BigDecimal deviation;
    @Value("${operations.fuel.min-peer-samples:3}") private int minPeerSamples;

    public Odometer odometer(long id) {
        admin();
        requireVehicle(vehicles.selectById(id));
        return mapper.odometer(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Odometer saveOdometer(long id, OdometerRequest input) {
        admin();
        var vehicle = requireVehicle(records.lockVehicle(id));
        historicalTime(input.measuredAt());
        var current = mapper.odometer(id);
        if (current != null) {
            check(!input.measuredAt().isBefore(current.measuredAt())
                    && input.odometerKm().compareTo(current.odometerKm()) >= 0, 409, "里程表读数与采集时间不能倒退");
            if (input.measuredAt().equals(current.measuredAt())) {
                check(input.odometerKm().compareTo(current.odometerKm()) == 0, 409, "该采集时间已记录不同里程");
                return current;
            }
        }
        mapper.saveOdometer(id, input);
        audit.success(OperationAction.ODOMETER_SAVE, id, vehicle.getFleetId());
        return mapper.odometer(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Plan createPlan(long id, PlanRequest input) {
        admin();
        var vehicle = requireVehicle(records.lockVehicle(id));
        check(input.intervalDays() != null || input.intervalKm() != null, 400, "至少设置一种保养周期");
        var nextDate = nextDate(input.intervalDays(), input.lastServiceDate());
        var nextKm = nextKm(input.intervalKm(), input.lastServiceOdometerKm());
        mapper.createPlan(id, input, nextDate, nextKm);
        var plan = mapper.plan(records.generatedId());
        audit.success(OperationAction.MAINTENANCE_CREATE, plan.id(), vehicle.getFleetId());
        return plan;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Plan completePlan(long id, ServiceCompletion input) {
        admin();
        var before = mapper.plan(id);
        check(before != null, 404, "保养计划不存在");
        var vehicle = requireVehicle(records.lockVehicle(before.vehicleId()));
        var plan = requirePlan(id);
        advancePlan(plan, input, vehicle);
        return mapper.plan(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Plan setPlanEnabled(long id, boolean enabled) {
        admin();
        var vehicle = requireVehicle(records.lockVehicle(requirePlan(id).vehicleId()));
        var plan = requirePlan(id);
        if (plan.enabled() != enabled) {
            mapper.setPlanEnabled(id, enabled);
            audit.success(OperationAction.MAINTENANCE_ENABLED, id, vehicle.getFleetId(), enabled ? "ENABLED" : "DISABLED");
        }
        return mapper.plan(id);
    }

    private void advancePlan(Plan plan, ServiceCompletion input, Vehicle vehicle) {
        check(plan.enabled(), 409, "保养计划已停用");
        check(plan.cycle() == input.cycle(), 409, "保养周期已更新，请刷新");
        check(plan.lastServiceDate() == null || input.date() != null && !input.date().isBefore(plan.lastServiceDate()),
                400, "保养日期不能早于该计划的上次日期");
        check(plan.lastServiceOdometerKm() == null || input.odometerKm() != null
                && input.odometerKm().compareTo(plan.lastServiceOdometerKm()) >= 0, 400, "保养里程不能小于上次里程");
        var nextDate = nextDate(plan.intervalDays(), input.date());
        var nextKm = nextKm(plan.intervalKm(), input.odometerKm());
        check(mapper.completePlan(plan.id(), input, nextDate, nextKm) == 1, 409, "保养周期已更新");
        audit.success(OperationAction.MAINTENANCE_COMPLETE, plan.id(), vehicle.getFleetId());
    }

    public List<Plan> plans(long vehicle, int page, int size) {
        admin();
        requireVehicle(vehicles.selectById(vehicle));
        return mapper.plans(vehicle, offset(page, size), size);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Repair reportRepair(long id, RepairRequest input) {
        var actor = admin();
        var vehicle = requireVehicle(records.lockVehicle(id));
        check(!mapper.openRepairExists(id), 409, "该车辆已有未结算维修工单");
        if (input.maintenancePlanId() != null) {
            check(requirePlan(input.maintenancePlanId()).vehicleId() == id, 409, "保养计划必须属于同一车辆");
        }
        var at = now();
        mapper.reportRepair(id, input, actor.userId(), at);
        var repair = mapper.repair(records.generatedId());
        mapper.recordRepairEvent(null, repair, repair.status(), null, actor.userId(), at);
        audit.success(OperationAction.REPAIR_CREATE, repair.id(), vehicle.getFleetId());
        return repair;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Repair transitionRepair(long id, RepairTransition input) {
        long actor = admin().userId();
        var vehicle = requireVehicle(records.lockVehicle(requireRepair(id).vehicleId()));
        var repair = requireRepair(id);
        check(input.status().follows(repair.status()), 409, "维修状态须按顺序处理");
        check(input.status() != RepairStatus.ASSIGNED || input.assignee() != null && !input.assignee().isBlank(),
                400, "派工时请填写维修负责人");
        check(input.status() != RepairStatus.SETTLED || input.cost() != null, 400, "结算时请填写费用，免费维修填 0");
        check(input.status() == RepairStatus.SETTLED || input.cost() == null, 400, "维修费用在结算时填写");
        check(input.status() == RepairStatus.DONE || input.completedOn() == null && input.completedOdometerKm() == null,
                400, "仅登记完工时可填写实际日期和里程");
        validateCompletionDate(input.completedOn());
        check(input.applyMaintenance() ? input.status() == RepairStatus.SETTLED && input.expectedCycle() != null
                : input.expectedCycle() == null, 400, "结算时明确选择更新保养计划并填写原周期");
        Integer appliedCycle = null;
        if (input.applyMaintenance()) {
            check(repair.maintenancePlanId() != null, 409, "工单没有关联保养计划");
            var plan = requirePlan(repair.maintenancePlanId());
            check(plan.vehicleId() == vehicle.getId(), 409, "保养计划必须属于同一车辆");
            advancePlan(plan, new ServiceCompletion(input.expectedCycle(), repair.completedOn(),
                    repair.completedOdometerKm()), vehicle);
            appliedCycle = input.expectedCycle();
        }
        var at = now();
        check(mapper.transitionRepair(id, repair.status(), input, at, appliedCycle) == 1, 409, "维修状态已变更");
        var saved = requireRepair(id);
        mapper.recordRepairEvent(repair.status(), saved, saved.status(), null, actor, at);
        audit.success(OperationAction.REPAIR_TRANSITION, id, vehicle.getFleetId(), input.status().name());
        return saved;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Repair rejectAcceptance(long id, RepairRejection input) {
        long actor = admin().userId();
        var vehicle = requireVehicle(records.lockVehicle(requireRepair(id).vehicleId()));
        var current = requireRepair(id);
        check(current.status() == RepairStatus.DONE, 409, "只有已完工的工单可以验收退回");
        String reason = requiredReason(input.reason());
        var at = now();
        check(mapper.reopenRepair(id, at) == 1, 409, "维修状态已变更");
        mapper.recordRepairEvent(current.status(), current, RepairStatus.IN_REPAIR, reason, actor, at);
        audit.success(OperationAction.REPAIR_TRANSITION, id, vehicle.getFleetId(), "ACCEPTANCE_REJECTED");
        return requireRepair(id);
    }

    public List<RepairEvent> repairHistory(long id) {
        admin();
        requireRepair(id);
        return mapper.repairEvents(id);
    }

    private Repair requireRepair(long id) {
        var repair = mapper.repair(id);
        check(repair != null, 404, "维修工单不存在");
        return repair;
    }

    private Plan requirePlan(long id) {
        var plan = mapper.plan(id);
        check(plan != null, 404, "保养计划不存在");
        return plan;
    }

    public List<Repair> repairs(long vehicle, RepairStatus status, int page, int size) {
        admin();
        requireVehicle(vehicles.selectById(vehicle));
        return mapper.repairs(vehicle, status, offset(page, size), size);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long createFuel(long vehicleId, FuelRequest input) {
        admin();
        var vehicle = requireVehicle(records.lockVehicle(vehicleId));
        historicalTime(input.fueledAt());
        check(!mapper.fuelTimeExists(vehicleId, input.fueledAt(), 0), 409, "该车辆在同一时间已有加油记录");
        mapper.insertFuel(vehicleId, input, amount(input));
        long id = records.generatedId();
        audit.success(OperationAction.FUEL_SAVE, id, vehicle.getFleetId(), "CREATED");
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long updateFuel(long id, FuelRequest input) {
        long actor = admin().userId();
        var vehicle = lockFuelVehicle(id);
        var before = writableFuel(id, input.expectedVersion());
        String reason = requiredReason(input.reason());
        historicalTime(input.fueledAt());
        check(!mapper.fuelTimeExists(vehicle.getId(), input.fueledAt(), id), 409, "该车辆在同一时间已有加油记录");
        check(mapper.updateFuel(id, vehicle.getId(), input, amount(input)) == 1, 409, "加油记录已变更");
        recordFuelRevision(before, actor, vehicle, "UPDATED", reason, now());
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long voidFuel(long id, FuelVoid input) {
        long actor = admin().userId();
        var vehicle = lockFuelVehicle(id);
        var before = writableFuel(id, input.expectedVersion());
        String reason = requiredReason(input.reason());
        var at = now();
        check(mapper.voidFuel(id, input.expectedVersion(), at) == 1, 409, "加油记录已变更");
        recordFuelRevision(before, actor, vehicle, "VOIDED", reason, at);
        return id;
    }

    public List<FuelRevision> fuelHistory(long id) {
        admin();
        check(mapper.fuelVehicle(id) != null, 404, "加油记录不存在");
        return mapper.fuelRevisions(id);
    }

    private Vehicle lockFuelVehicle(long id) {
        Long vehicleId = mapper.fuelVehicle(id);
        check(vehicleId != null, 404, "加油记录不存在");
        return requireVehicle(records.lockVehicle(vehicleId));
    }

    private FuelSnapshot writableFuel(long id, Long expectedVersion) {
        check(expectedVersion != null && expectedVersion > 0, 400, "请填写原记录版本");
        var row = mapper.fuelSnapshot(id);
        check(row != null, 404, "加油记录不存在");
        check(row.voidedAt() == null && row.version() == expectedVersion, 409, "加油记录已修正或作废，请刷新");
        return row;
    }

    private void recordFuelRevision(FuelSnapshot before, long actor, Vehicle vehicle, String action, String reason,
                                    LocalDateTime at) {
        var after = mapper.fuelSnapshot(before.id());
        try {
            mapper.recordFuelRevision(after.id(), after.version(), action, actor, reason, json.writeValueAsString(before),
                    json.writeValueAsString(after), at);
        } catch (JsonProcessingException invalidSnapshot) {
            throw new IllegalStateException("无法保存加油记录修正历史", invalidSnapshot);
        }
        audit.success(OperationAction.FUEL_SAVE, after.id(), vehicle.getFleetId(), action);
    }

    private static String requiredReason(String reason) {
        check(reason != null && !reason.isBlank(), 400, "请填写操作原因");
        return reason.strip();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FuelOverview fuel(long id, LocalDate start, LocalDate end, int page, int size) {
        admin();
        var vehicle = requireVehicle(vehicles.selectById(id));
        dateRange(start, end);
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.atStartOfDay();
        long offset = offset(page, size);
        PeerRate peer = vehicle.getVehicleType() == null || vehicle.getVehicleType().isBlank() ? new PeerRate(0, null)
                : mapper.peerRate(vehicle.getFleetId(), vehicle.getVehicleType(), id, from, to);
        BigDecimal baseline = peer.samples() >= minPeerSamples ? peer.average() : null;
        var rows = mapper.fuel(id, from, to, offset, size).stream().map(row -> new FuelRow(row, classify(row, baseline))).toList();
        return new FuelOverview(id, baseline, peer.samples(), deviation, rows);
    }

    private FuelStatus classify(Fuel row, BigDecimal baseline) {
        if (row.calculationStatus() != FuelCalculation.VALID) {
            return FuelStatus.valueOf(row.calculationStatus().name());
        }
        if (baseline == null || baseline.signum() == 0) { return FuelStatus.INSUFFICIENT_PEERS; }
        return row.litersPer100Km().compareTo(baseline.multiply(BigDecimal.ONE.add(deviation))) > 0
                ? FuelStatus.ABNORMAL : FuelStatus.NORMAL;
    }

    private static BigDecimal amount(FuelRequest input) {
        BigDecimal value = input.liters().multiply(input.unitPrice()).setScale(2, RoundingMode.HALF_UP);
        check(value.compareTo(DB_AMOUNT_LIMIT) <= 0, 400, "加油金额超出范围");
        return value;
    }

    private static void historicalTime(LocalDateTime at) {
        check(at.getYear() >= 1000 && !at.isAfter(now()), 400, "记录时间应为有效的过去或当前时间");
    }

    private static LocalDate nextDate(Integer interval, LocalDate date) {
        validateCompletionDate(date);
        if (interval == null) { return null; }
        check(date != null, 400, "按日期保养需填写上次保养日期");
        return date.plusDays(interval);
    }

    private static void validateCompletionDate(LocalDate date) {
        if (date != null) {
            check(date.getYear() >= 1000 && !date.isAfter(now().toLocalDate()), 400, "实际完工日期无效");
        }
    }

    private static BigDecimal nextKm(BigDecimal interval, BigDecimal km) {
        if (interval == null) { return null; }
        check(km != null, 400, "按里程保养需填写上次保养里程");
        BigDecimal target = km.add(interval);
        check(target.compareTo(DB_AMOUNT_LIMIT) <= 0, 400, "保养里程超出范围");
        return target;
    }

    private static Vehicle requireVehicle(Vehicle vehicle) {
        check(vehicle != null, 404, "车辆不存在");
        return vehicle;
    }
}
