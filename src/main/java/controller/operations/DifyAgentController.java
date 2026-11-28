package controller.operations;

import common.config.DifyAgentSecurityConfig;
import common.exception.BusinessException;
import common.response.Result;
import dto.operations.DifyAgent.*;
import dto.operations.TransportOrders.Draft;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import mapper.DifyAgentMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import service.operations.DifyDraftService;
import service.operations.DifyViolationService;
import service.operations.WorkforceConflict;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(DifyAgentSecurityConfig.PATH)
@RequiredArgsConstructor
public class DifyAgentController {
    private final DifyAgentMapper mapper;
    private final DifyDraftService drafts;
    private final DifyViolationService violations;

    @GetMapping("/fleets")
    public Result<Catalog<FleetOption>> fleets(@RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return Result.success(catalog(mapper.fleets(offset, limit + 1), limit));
    }

    @GetMapping("/drivers")
    public Result<Catalog<DriverOption>> drivers(@RequestParam(required = false) @Positive Long fleetId,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return Result.success(catalog(mapper.drivers(fleetId, offset, limit + 1), limit));
    }

    @PostMapping("/orders/preview")
    public Result<Preview<Draft>> order(@Valid @RequestBody Draft request) {
        return Result.success(drafts.previewOrder(request));
    }

    @PostMapping("/shifts/preview")
    public Result<Preview<SchedulePlan>> shifts(@Valid @RequestBody ScheduleRequest request) {
        return Result.success(drafts.previewShifts(request));
    }

    @PostMapping("/confirm")
    public Result<Confirmation> confirm(@Valid @RequestBody ConfirmRequest request) {
        return Result.success(drafts.confirm(request.draftId()));
    }

    @GetMapping("/violations")
    public Result<ViolationSummary> violations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) @Positive Long fleetId,
            @RequestParam(required = false) @Positive Long driverId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int top,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return Result.success(violations.summary(startDate, endDate, fleetId, driverId, top, page, pageSize));
    }

    @ExceptionHandler(WorkforceConflict.class)
    ResponseEntity<Result<Map<String, String>>> conflict(WorkforceConflict exception) {
        return ResponseEntity.status(409).body(new Result<>(409, exception.getMessage(),
                Map.of("reason", exception.getReason().name())));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Result<Void>> business(BusinessException exception) {
        return ResponseEntity.status(exception.getCode()).body(Result.error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<Map<String, String>>> invalidBody(MethodArgumentNotValidException exception) {
        var field = exception.getBindingResult().getFieldError();
        var data = Map.of("field", field == null ? "" : field.getField());
        return ResponseEntity.badRequest().body(new Result<>(400, "请求字段缺失或不符合约束", data));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<Result<Void>> invalidInput() {
        return ResponseEntity.badRequest().body(Result.error(400, "请求参数格式或取值无效"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Result<Void>> databaseUnavailable() {
        return ResponseEntity.status(503).body(Result.error(503, "Agent 数据库操作失败，请检查部署与迁移"));
    }

    private static <T> Catalog<T> catalog(List<T> rows, int limit) {
        return new Catalog<>(rows.subList(0, Math.min(limit, rows.size())), rows.size() > limit);
    }
}
