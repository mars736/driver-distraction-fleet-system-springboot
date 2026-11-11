package controller.alert;

import common.exception.BusinessException;
import common.response.PageResult;
import common.response.Result;
import dto.alert.AlertQueryRequest;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import service.alert.AlertQueryService;
import vo.alert.AlertView;

import java.io.IOException;
import java.nio.file.Files;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertQueryController {
    private final AlertQueryService service;

    @GetMapping
    public Result<PageResult<AlertView>> list(@Valid @ModelAttribute AlertQueryRequest request) {
        return Result.success(service.list(request));
    }

    @GetMapping("/{id}")
    public Result<AlertView> detail(@PathVariable long id) { return Result.success(service.detail(id)); }

    @GetMapping("/{id}/key-frame")
    public ResponseEntity<Resource> keyFrame(@PathVariable long id, HttpServletRequest request) throws IOException {
        var path = service.keyFrame(id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .contentLength(Files.size(path)).cacheControl(CacheControl.noStore())
                .header("Content-Disposition", ContentDisposition.inline().filename("alert-" + id + ".jpg").build().toString())
                .body("HEAD".equals(request.getMethod()) ? null : new FileSystemResource(path));
    }

    // 本模块保持真实 HTTP 状态码；不改变其他既有业务接口的异常处理行为。
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> businessError(BusinessException exception) {
        return ResponseEntity.status(exception.getCode()).body(Result.error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler({BindException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> invalidQuery(Exception exception) {
        return ResponseEntity.badRequest().body(Result.error(400, "查询参数格式或范围不正确"));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<Void>> fileError(IOException exception) {
        return ResponseEntity.internalServerError().body(Result.error(500, "关键帧读取失败"));
    }
}
