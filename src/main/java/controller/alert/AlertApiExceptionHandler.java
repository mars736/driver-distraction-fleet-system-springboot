package controller.alert;

import common.exception.BusinessException;
import controller.camera.CameraController;
import controller.score.DriverScoreController;
import controller.telemetry.VehicleTelemetryController;
import controller.telemetry.VehicleLiveStatusController;
import controller.audit.OperationLogController;
import controller.operations.FleetCareController;
import controller.operations.OperationsReminderController;
import controller.operations.TransportOrderController;
import controller.operations.WorkforceController;
import controller.operations.ViolationDispatchController;
import common.response.Result;
import service.alert.AlertReviewConflict;
import service.alert.AppealReviewConflict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;
import java.io.IOException;

/** 为业务接口保留实际 HTTP 状态码和统一错误正文。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AlertQueryController.class, AlertReviewController.class,
        AppealController.class, AlertLiveController.class, AlertReportController.class,
        DriverScoreController.class, VehicleTelemetryController.class, VehicleLiveStatusController.class,
        OperationLogController.class, WorkforceController.class, FleetCareController.class,
        OperationsReminderController.class, ViolationDispatchController.class, CameraController.class,
        TransportOrderController.class})
@Slf4j
public class AlertApiExceptionHandler {
    @ExceptionHandler(AlertReviewConflict.class)
    public ResponseEntity<Result<AlertReviewConflict.Details>> reviewConflict(AlertReviewConflict exception) {
        return ResponseEntity.status(exception.getCode()).contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(exception.getCode(), exception.getMessage(), exception.getDetails()));
    }

    @ExceptionHandler(AppealReviewConflict.class)
    public ResponseEntity<Result<AppealReviewConflict.Details>> appealConflict(AppealReviewConflict exception) {
        return ResponseEntity.status(exception.getCode()).contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(exception.getCode(), exception.getMessage(), exception.getDetails()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> businessError(BusinessException exception) {
        // 下载请求可能只接受 PDF/图片，但失败时仍要返回约定的 JSON，不能继续按文件格式协商。
        return ResponseEntity.status(exception.getCode()).contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler({BindException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(400, "请求参数格式或范围不正确"));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<Void>> fileError(IOException exception) {
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(500, "文件操作失败"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpectedError(Exception exception) {
        // 框架的 415/406 等响应已有明确状态，不能被兜底处理误报为服务器故障。
        if (exception instanceof ErrorResponse error) {
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Result.error(error.getStatusCode().value(), "请求类型或响应格式不受支持"));
        }
        log.error("业务接口执行失败，异常类型={}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(500, "请求处理失败，请稍后重试"));
    }
}
