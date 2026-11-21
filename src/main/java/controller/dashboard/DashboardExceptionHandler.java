package controller.dashboard;

import common.exception.BusinessException;
import common.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DashboardController.class)
public class DashboardExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> error(Exception error) {
        if (error instanceof BusinessException business) {
            return ResponseEntity.status(business.getCode()).contentType(MediaType.APPLICATION_JSON)
                    .body(Result.error(business.getCode(), business.getMessage()));
        }
        if (error instanceof MethodArgumentTypeMismatchException) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(Result.error(400, "工作台参数格式不正确"));
        }
        if (error instanceof ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            return ResponseEntity.status(status).headers(framework.getHeaders()).contentType(MediaType.APPLICATION_JSON)
                    .body(Result.error(status, "请求不受支持"));
        }
        log.error("工作台查询失败，异常类型={}", error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).body(Result.error(500, "工作台查询失败"));
    }
}
