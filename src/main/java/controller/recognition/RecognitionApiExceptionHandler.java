package controller.recognition;

import common.exception.BusinessException;
import common.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 仅作用于新增查询模块，沿用框架状态码，不改变现有档案接口的异常约定。 */
@RestControllerAdvice(assignableTypes = RecognitionQueryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RecognitionApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> business(BusinessException exception) {
        return json(exception.getCode(), new HttpHeaders(), exception.getMessage());
    }
    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = switch (status.value()) {
            case 400 -> "查询参数格式或范围不正确";
            case 406 -> "请求的响应格式不受支持";
            case 415 -> "请求类型不受支持";
            default -> "请求无法处理";
        };
        return json(status.value(), headers, message);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception exception) {
        log.error("识别记录查询失败，异常类型={}", exception.getClass().getSimpleName());
        return json(500, new HttpHeaders(), "识别记录查询失败，请稍后重试");
    }
    private ResponseEntity<Object> json(int status, HttpHeaders headers, String message) {
        // 即使前端下载图片时只接受 image/jpeg，错误正文仍明确返回 JSON。
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON).body(Result.error(status, message));
    }
}
