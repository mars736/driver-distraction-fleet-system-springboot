package controller.alert;

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
import org.springframework.web.bind.annotation.*;
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

}
