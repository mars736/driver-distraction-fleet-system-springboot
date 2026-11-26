package service.operations;

import common.constant.CommonConstant;
import common.security.UserContext;
import dto.operations.Workforce.*;
import entity.Fleet;
import entity.User;
import lombok.RequiredArgsConstructor;
import mapper.FleetMapper;
import mapper.OperationsRecordMapper;
import mapper.UserMapper;
import mapper.WorkforceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.audit.OperationAction;
import service.audit.OperationLogService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static service.operations.OperationsAccess.*;
import static service.operations.WorkforceConflict.Reason.*;
import static service.operations.WorkforceConflict.require;

@Service
@RequiredArgsConstructor
public class WorkforceService {
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final WorkforceMapper mapper;
    private final FleetMapper fleets;
    private final UserMapper users;
    private final OperationsRecordMapper records;
    private final OperationLogService audit;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Shift saveShift(ShiftRequest input) {
        admin();
        var driver = lockDriver(input.driverId());
        LocalDate date = input.date();
        check(date.getYear() >= 1000 && date.getYear() <= 9998, 400, "排班日期无效");
        var before = mapper.shift(driver.getId(), date);
        check(before == null || mapper.attendance(before.id()) == null, 409, "已有打卡记录的班次不能修改");

        LocalDateTime start = input.startsAt();
        LocalDateTime end = input.endsAt();
        if (input.type() == ShiftType.REST) {
            check(start == null && end == null, 400, "休息班次不填写起止时间");
            var restStart = date.atStartOfDay();
            require(!mapper.taskConflict(driver.getId(), null, restStart, restStart.plusDays(1), now()),
                    REST_OVERLAPS_TASK);
        } else {
            if (start == null && end == null) {
                start = date.atTime(input.type() == ShiftType.EARLY ? 8 : 16, 0);
                end = start.plusHours(8);
            }
            window(start, end, 1);
            check(start.toLocalDate().equals(date), 400, "班次开始时间应在排班当天");
            check(!mapper.shiftOverlap(driver.getId(), date, start, end), 409, "班次与相邻日期的排班重叠");
            require(!mapper.leaveOverlap(driver.getId(), 0, start, end), SHIFT_OVERLAPS_LEAVE);
        }
        mapper.saveShift(driver.getId(), driver.getFleetId(), date, input.type(), start, end);
        var result = mapper.shift(driver.getId(), date);
        audit.success(OperationAction.SHIFT_SAVE, result.id(), driver.getFleetId(), input.type().name());
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ShiftAttendance> schedule(Long requested, LocalDate start, LocalDate end) {
        long driver = driverScope(requested);
        dateRange(start, end);
        var attendance = mapper.attendanceRange(driver, start, end).stream()
                .collect(Collectors.toMap(Attendance::shiftId, Function.identity()));
        return mapper.shifts(driver, start, end).stream()
                .map(shift -> new ShiftAttendance(shift, attendance.get(shift.id()))).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Attendance punch(Punch input, boolean entering) {
        var driver = lockDriver(driver().userId());
        var shift = mapper.shift(driver.getId(), input.shiftDate());
        check(shift != null && shift.shiftType() != ShiftType.REST, 404, "该日没有需要打卡的班次");
        var current = mapper.attendance(shift.id());
        if (entering && current != null || !entering && current != null && current.clockOutAt() != null) {
            return current;
        }
        validPosition(input.position());
        var at = now();
        check(!mapper.runningDriver(driver.getId()), 409, "运输任务执行中，请使用任务报到入口");
        if (entering) {
            check(!at.isBefore(shift.startsAt().minusHours(2)) && at.isBefore(shift.endsAt()), 409, "当前不在签到时间内");
            check(!mapper.onLeave(driver.getId(), at), 409, "当前处于已批准的请假时段");
            mapper.clockIn(shift.id(), at, input.position());
        } else {
            check(current != null && !at.isBefore(current.clockInAt()), 409, "请先签到再签退");
            check(mapper.clockOut(shift.id(), at, input.position()) == 1, 409, "签到记录已变更");
        }
        audit.success(OperationAction.SHIFT_PUNCH, shift.id(), driver.getFleetId(), entering ? "IN" : "OUT");
        return mapper.attendance(shift.id());
    }

    public List<DutyDriver> onDuty(Long fleetId, int page, int size) {
        admin();
        check(fleetId == null || fleetId > 0, 400, "车队 ID 无效");
        return mapper.onDuty(fleetId, now(), offset(page, size), size);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Leave requestLeave(LeaveRequest input) {
        var driver = lockDriver(driver().userId());
        window(input.startsAt(), input.endsAt(), 366);
        var at = now();
        check(input.endsAt().isAfter(at), 400, "请假结束时间应晚于当前时间");
        check(!mapper.leaveOverlap(driver.getId(), 0, input.startsAt(), input.endsAt()), 409, "已有重叠请假");
        mapper.requestLeave(driver.getId(), driver.getFleetId(), input, at);
        var row = mapper.leave(records.generatedId());
        audit.success(OperationAction.LEAVE_CREATE, row.id(), driver.getFleetId());
        return row;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Leave decideLeave(long id, LeaveDecision input) {
        long actor = admin().userId();
        var before = mapper.leave(id);
        check(before != null, 404, "请假记录不存在");
        lockDriver(before.driverId());
        var leave = mapper.leave(id);
        check(leave.status() == LeaveStatus.PENDING, 409, "该申请已审批");
        var at = now();
        if (input.approved()) {
            check(!mapper.leaveOverlap(leave.driverId(), id, leave.startsAt(), leave.endsAt()), 409, "已有重叠的获批请假");
            require(!mapper.workShiftOverlap(leave.driverId(), leave.startsAt(), leave.endsAt()), LEAVE_OVERLAPS_SHIFT);
            require(!mapper.taskConflict(leave.driverId(), null, leave.startsAt(), leave.endsAt(), at), LEAVE_OVERLAPS_TASK);
        }
        var status = input.approved() ? LeaveStatus.APPROVED : LeaveStatus.REJECTED;
        check(mapper.decideLeave(id, status, actor, at, input.note()) == 1, 409, "审批状态已变更");
        audit.success(OperationAction.LEAVE_DECIDE, id, leave.fleetId(), status.name());
        return mapper.leave(id);
    }

    public List<Leave> leaves(Long driverId, Long fleetId, LeaveStatus status, int page, int size) {
        return mapper.leaves(listDriver(driverId, fleetId), fleetId, status, null, offset(page, size), size);
    }

    public CursorPage<Leave> leavePage(Long driverId, Long fleetId, LeaveStatus status, Long beforeId, int size) {
        var owner = listDriver(driverId, fleetId);
        check(beforeId == null || beforeId > 0, 400, "分页游标必须为正数");
        return cursorPage(mapper.leaves(owner, fleetId, status, beforeId, offset(1, size), size + 1), size, Leave::id);
    }

    public Leave leaveDetail(long id) {
        var owner = listDriver(null, null);
        check(id > 0, 400, "请假 ID 无效");
        var leave = mapper.leave(id);
        check(leave != null && (owner == null || leave.driverId() == owner), 404, "请假记录不存在");
        return leave;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task createTask(TaskRequest input, String requestKey) {
        long actor = admin().userId();
        check(requestKey == null || REQUEST_KEY.matcher(requestKey).matches(), 400,
                "申请标识必须为 1—128 个字母、数字或 . _ : -");
        input = new TaskRequest(input.driverId(), input.vehicleId(), input.type(), input.description(),
                input.startsAt().truncatedTo(ChronoUnit.MILLIS), input.endsAt().truncatedTo(ChronoUnit.MILLIS));
        window(input.startsAt(), input.endsAt(), 366);
        var existing = findReplay(actor, requestKey, input);
        if (existing != null) { return existing; }

        // 与定位和车队启停保持相同的加锁顺序。
        var preview = requireDriver(users.selectById(input.driverId()));
        var fleet = fleets.lockById(preview.getFleetId());
        var driver = lockDriver(input.driverId());
        existing = findReplay(actor, requestKey, input);
        if (existing != null) { return existing; }
        verifyDispatchFleet(fleet, driver);
        long vehicle = input.vehicleId();
        verifyBinding(driver, vehicle);
        var at = now();
        check(input.endsAt().isAfter(at), 400, "任务结束时间应晚于当前时间");
        var today = at.toLocalDate().atStartOfDay();
        require(!mapper.restOverlap(driver.getId(), today, today.plusDays(1))
                && !mapper.restOverlap(driver.getId(), input.startsAt(), input.endsAt()), TASK_OVERLAPS_REST);
        check(mapper.punchedOnDuty(driver.getId(), at)
                && !mapper.onLeave(driver.getId(), at), 409, "司机当前不在岗");
        require(!mapper.leaveOverlap(driver.getId(), 0, input.startsAt(), input.endsAt()), TASK_OVERLAPS_LEAVE);
        check(!mapper.running(driver.getId(), vehicle, 0)
                && !mapper.taskConflict(driver.getId(), vehicle, input.startsAt(), input.endsAt(), at),
                409, "司机或车辆已有冲突任务");
        try {
            mapper.createTask(input, driver.getFleetId(), actor, at, requestKey);
        } catch (DuplicateKeyException duplicate) {
            existing = findReplay(actor, requestKey, input);
            if (existing == null) { throw duplicate; }
            return existing;
        }
        var task = mapper.task(records.generatedId());
        audit.success(OperationAction.TASK_CREATE, task.id(), task.fleetId());
        return task;
    }

    private Task findReplay(long actor, String requestKey, TaskRequest input) {
        if (requestKey == null) { return null; }
        var task = mapper.taskByRequest(actor, requestKey);
        if (task == null) { return null; }
        require(task.driverId() == input.driverId() && task.vehicleId() == input.vehicleId()
                && task.taskType().equals(input.type()) && task.description().equals(input.description())
                && task.startsAt().equals(input.startsAt()) && task.endsAt().equals(input.endsAt()),
                TASK_REQUEST_KEY_REUSED);
        return task;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task reportTask(long id, Position position, boolean departure) {
        long actor = driver().userId();
        var task = mapper.task(id);
        check(task != null && task.driverId() == actor, 404, "任务不存在");
        var fleet = departure ? fleets.lockById(task.fleetId()) : null;
        var driver = lockDriver(actor);
        task = mapper.task(id);
        check(task != null && task.driverId() == actor, 404, "任务不存在");
        validPosition(position);
        var at = now();
        if (departure) {
            if (task.status() == TaskStatus.RUNNING) { return task; }
            require(task.status() == TaskStatus.PENDING, TASK_NOT_PENDING);
            verifyDispatchFleet(fleet, driver);
            verifyBinding(driver, task.vehicleId());
            check(!at.isBefore(task.startsAt()) && at.isBefore(task.endsAt()), 409, "任务暂不可出发");
            require(!mapper.restOverlap(driver.getId(), at, task.endsAt()), TASK_OVERLAPS_REST);
            require(!mapper.leaveOverlap(driver.getId(), 0, at, task.endsAt()), TASK_OVERLAPS_LEAVE);
            check(!mapper.running(driver.getId(), task.vehicleId(), id), 409, "已有执行中任务");
            check(mapper.depart(id, at, position) == 1, 409, "任务状态已变更");
        } else {
            if (task.status() == TaskStatus.COMPLETED) { return task; }
            check(task.status() == TaskStatus.RUNNING && !at.isBefore(task.departedAt()), 409, "任务尚未出发");
            check(mapper.finishTask(id, at, position) == 1, 409, "任务状态已变更");
        }
        audit.success(OperationAction.TASK_REPORT, task.id(), task.fleetId(), departure ? "DEPARTED" : "COMPLETED");
        return mapper.task(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task cancelTask(long id) {
        admin();
        var task = mapper.task(id);
        check(task != null, 404, "任务不存在");
        records.lockUser(task.driverId());
        require(mapper.cancelTask(id, now()) == 1, TASK_NOT_PENDING);
        audit.success(OperationAction.TASK_CANCEL, id, task.fleetId());
        return mapper.task(id);
    }

    public List<Task> tasks(Long driverId, Long fleetId, TaskStatus status, int page, int size) {
        return mapper.tasks(listDriver(driverId, fleetId), fleetId, status, null, offset(page, size), size);
    }

    public CursorPage<Task> taskPage(Long driverId, Long fleetId, TaskStatus status, Long beforeId, int size) {
        var owner = listDriver(driverId, fleetId);
        check(beforeId == null || beforeId > 0, 400, "分页游标必须为正数");
        return cursorPage(mapper.tasks(owner, fleetId, status, beforeId, offset(1, size), size + 1), size, Task::id);
    }

    public Task taskDetail(long id) {
        var owner = listDriver(null, null);
        check(id > 0, 400, "任务 ID 无效");
        var task = mapper.task(id);
        check(task != null && (owner == null || task.driverId() == owner), 404, "任务不存在");
        return task;
    }

    private static <T> CursorPage<T> cursorPage(List<T> rows, int size, ToLongFunction<T> id) {
        boolean hasNext = rows.size() > size;
        var records = hasNext ? List.copyOf(rows.subList(0, size)) : rows;
        return new CursorPage<>(records, hasNext, hasNext ? id.applyAsLong(records.getLast()) : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DutyStatus dutyStatus(Long requestedDriver) {
        long id = driverScope(requestedDriver);
        var at = now();
        if (mapper.runningDriver(id)) { return DutyStatus.ON_TASK; }
        if (mapper.onLeave(id, at)) { return DutyStatus.ON_LEAVE; }
        return mapper.punchedOnDuty(id, at) ? DutyStatus.ON_DUTY : DutyStatus.OFF_DUTY;
    }

    private static Long listDriver(Long requested, Long fleetId) {
        if (CommonConstant.ROLE_PLATFORM_ADMIN.equals(UserContext.get().role())) {
            check((requested == null || requested > 0) && (fleetId == null || fleetId > 0), 400, "车队或司机 ID 无效");
            return requested;
        }
        check(fleetId == null, 400, "司机端按本人查询");
        return driverScope(requested);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Totals totals(Long requested, LocalDate start, LocalDate end) {
        long driver = driverScope(requested);
        dateRange(start, end);
        var at = now();
        var startAt = start.atStartOfDay();
        var endAt = end.atStartOfDay();
        var tasks = new TreeSet<LocalDate>();
        for (var task : mapper.actualTasks(driver, startAt, endAt, at)) {
            addDates(tasks, start, end, task.departedAt(), task.endedAt() == null ? at : task.endedAt());
        }
        var leaves = new TreeSet<LocalDate>();
        for (var leave : mapper.approved(driver, startAt, endAt)) {
            addDates(leaves, start, end, leave.startsAt(), leave.endsAt());
        }
        var attendance = mapper.attendanceRange(driver, start, end).stream()
                .collect(Collectors.toMap(Attendance::shiftId, Function.identity()));
        var attended = new TreeSet<>(tasks);
        int punched = 0, late = 0, early = 0;
        for (var shift : mapper.shifts(driver, start, end)) {
            var punch = attendance.get(shift.id());
            if (punch == null) { continue; }
            attended.add(shift.shiftDate());
            punched++;
            if (punch.clockInAt().isAfter(shift.startsAt())) { late++; }
            if (punch.clockOutAt() != null && punch.clockOutAt().isBefore(shift.endsAt())) { early++; }
        }
        return new Totals(driver, start, end, attended.size(), tasks.size(), punched, late, early, leaves.size(),
                List.copyOf(attended));
    }

    private static void addDates(TreeSet<LocalDate> dates, LocalDate start, LocalDate end,
                                 LocalDateTime first, LocalDateTime until) {
        if (!first.isBefore(until)) { return; }
        LocalDate day = first.toLocalDate().isBefore(start) ? start : first.toLocalDate();
        while (day.isBefore(end) && day.atStartOfDay().isBefore(until)) {
            dates.add(day);
            day = day.plusDays(1);
        }
    }

    private static void validPosition(Position position) {
        check(Double.isFinite(position.latitude()) && Double.isFinite(position.longitude()), 400, "定位坐标无效");
    }

    private User lockDriver(long id) {
        return requireDriver(records.lockUser(id));
    }

    private User requireDriver(User driver) {
        check(driver != null && CommonConstant.ROLE_DRIVER.equals(driver.getRole())
                && Boolean.TRUE.equals(driver.getEnabled()) && driver.getFleetId() != null, 404, "未找到启用的司机");
        return driver;
    }

    private void verifyDispatchFleet(Fleet fleet, User driver) {
        require(fleet != null && Objects.equals(fleet.getId(), driver.getFleetId()), FLEET_BINDING_CHANGED);
        require(Boolean.TRUE.equals(fleet.getEnabled()), FLEET_STOPPED);
    }

    private void verifyBinding(User driver, long vehicleId) {
        var vehicle = records.lockVehicle(vehicleId);
        check(vehicle != null, 404, "车辆不存在");
        check(Objects.equals(driver.getVehicleId(), vehicleId) && Objects.equals(driver.getFleetId(), vehicle.getFleetId()),
                409, "司机未绑定同车队的车辆");
    }
}
