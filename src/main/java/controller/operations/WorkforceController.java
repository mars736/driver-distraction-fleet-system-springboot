package controller.operations;

import common.response.PageResult;
import common.response.Result;
import dto.operations.Workforce.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import service.operations.WorkforceConflict;
import service.operations.WorkforceService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/operations/workforce")
@RequiredArgsConstructor
public class WorkforceController {
    private final WorkforceService service;

    @PutMapping("/shifts")
    public Result<Shift> shift(@Valid @RequestBody ShiftRequest request) { return Result.success(service.saveShift(request)); }

    @GetMapping("/shifts")
    public Result<List<ShiftAttendance>> schedule(@RequestParam(required = false) Long driverId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return Result.success(service.schedule(driverId, start, end));
    }

    @GetMapping("/shifts/page")
    public Result<PageResult<DriverSchedule>> schedulePage(@RequestParam Long fleetId,
            @RequestParam(required = false) Long driverId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.schedulePage(fleetId, driverId, start, end, page, pageSize));
    }

    @PostMapping("/attendance/in")
    public Result<Attendance> clockIn(@Valid @RequestBody Punch request) { return Result.success(service.punch(request, true)); }

    @PostMapping("/attendance/out")
    public Result<Attendance> clockOut(@Valid @RequestBody Punch request) { return Result.success(service.punch(request, false)); }

    @GetMapping("/attendance/summary")
    public Result<Totals> totals(@RequestParam(required = false) Long driverId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return Result.success(service.totals(driverId, start, end));
    }

    @GetMapping("/on-duty")
    public Result<List<DutyDriver>> onDuty(@RequestParam(required = false) Long fleetId,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.onDuty(fleetId, page, pageSize));
    }

    @GetMapping("/status")
    public Result<DutyStatus> dutyStatus(@RequestParam(required = false) Long driverId) {
        return Result.success(service.dutyStatus(driverId));
    }

    @PostMapping("/leaves")
    public Result<Leave> leave(@Valid @RequestBody LeaveRequest request) { return Result.success(service.requestLeave(request)); }

    @GetMapping("/leaves")
    public Result<List<Leave>> leaves(@RequestParam(required = false) Long driverId, @RequestParam(required = false) Long fleetId,
                                      @RequestParam(required = false) LeaveStatus status,
                                      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.leaves(driverId, fleetId, status, page, pageSize));
    }

    @GetMapping("/leaves/page")
    public Result<CursorPage<Leave>> leavePage(@RequestParam(required = false) Long driverId,
            @RequestParam(required = false) Long fleetId, @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.leavePage(driverId, fleetId, status, beforeId, pageSize));
    }

    @GetMapping("/leaves/{id}")
    public Result<Leave> leaveDetail(@PathVariable long id) { return Result.success(service.leaveDetail(id)); }

    @PostMapping("/leaves/{id}/decision")
    public Result<Leave> decide(@PathVariable long id, @Valid @RequestBody LeaveDecision request) {
        return Result.success(service.decideLeave(id, request));
    }

    @PostMapping("/tasks")
    public Result<Task> createTask(@Valid @RequestBody TaskRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String requestKey) {
        return Result.success(service.createTask(request, requestKey));
    }

    @GetMapping("/tasks")
    public Result<List<Task>> tasks(@RequestParam(required = false) Long driverId, @RequestParam(required = false) Long fleetId,
                                    @RequestParam(required = false) TaskStatus status,
                                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.tasks(driverId, fleetId, status, page, pageSize));
    }

    @GetMapping("/tasks/page")
    public Result<CursorPage<Task>> taskPage(@RequestParam(required = false) Long driverId,
            @RequestParam(required = false) Long fleetId, @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.taskPage(driverId, fleetId, status, beforeId, pageSize));
    }

    @GetMapping("/tasks/{id}")
    public Result<Task> taskDetail(@PathVariable long id) { return Result.success(service.taskDetail(id)); }

    @PostMapping("/tasks/{id}/departure")
    public Result<Task> depart(@PathVariable long id, @Valid @RequestBody Position position) {
        return Result.success(service.reportTask(id, position, true));
    }

    @PostMapping("/tasks/{id}/completion")
    public Result<Task> finish(@PathVariable long id, @Valid @RequestBody Position position) {
        return Result.success(service.reportTask(id, position, false));
    }

    @PostMapping("/tasks/{id}/cancel")
    public Result<Task> cancel(@PathVariable long id) { return Result.success(service.cancelTask(id)); }

    @ExceptionHandler(WorkforceConflict.class)
    public ResponseEntity<Result<ConflictDetails>> conflict(WorkforceConflict exception) {
        return ResponseEntity.status(exception.getCode()).body(new Result<>(exception.getCode(), exception.getMessage(),
                new ConflictDetails(exception.getReason())));
    }

    public record ConflictDetails(WorkforceConflict.Reason reason) { }
}
