package controller.alert;

import common.exception.BusinessException;
import common.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.io.IOException;

/** 告警模块返回真实 HTTP 状态码，不改变其他既有业务模块的约定。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AlertQueryController.class, AlertReviewController.class})
@Slf4j
public class AlertApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> businessError(BusinessException exception) {
        return ResponseEntity.status(exception.getCode()).body(Result.error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler({BindException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Result.error(400, "请求参数格式或范围不正确"));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<Void>> fileError(IOException exception) {
        return ResponseEntity.internalServerError().body(Result.error(500, "关键帧读取失败"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpectedError(Exception exception) {
        log.error("告警接口执行失败，异常类型={}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Result.error(500, "告警操作失败，请稍后重试"));
    }
}
