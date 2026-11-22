package controller.audit;

import common.response.PageResult;
import common.response.Result;
import entity.OperationLog;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import service.audit.OperationAction;
import service.audit.OperationLogService;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
public class OperationLogController {
    private final OperationLogService service;

    @GetMapping("/api/operation-logs")
    public Result<PageResult<OperationLog>> list(
            @RequestParam(required = false) Long fleetId,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) OperationAction action,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(service.list(fleetId, actorId, action, targetId, startTime, endTime, page, pageSize));
    }
}
