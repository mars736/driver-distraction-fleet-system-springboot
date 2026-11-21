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
import vo.alert.AlertTimelineItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        return image(service.keyFrame(id), "alert-" + id + ".jpg", request);
    }

    @GetMapping("/{id}/key-frames")
    public Result<List<AlertTimelineItem>> timeline(@PathVariable long id) {
        return Result.success(service.timeline(id));
    }

    @GetMapping("/{id}/key-frames/{offset}")
    public ResponseEntity<Resource> timelineFrame(@PathVariable long id, @PathVariable int offset,
            HttpServletRequest request) throws IOException {
        return image(service.timelineFrame(id, offset), "alert-" + id + "-" + offset + ".jpg", request);
    }

    private ResponseEntity<Resource> image(Path path, String filename, HttpServletRequest request) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .contentLength(Files.size(path)).cacheControl(CacheControl.noStore())
                .header("Content-Disposition", ContentDisposition.inline().filename(filename).build().toString())
                .body("HEAD".equals(request.getMethod()) ? null : new FileSystemResource(path));
    }

}
