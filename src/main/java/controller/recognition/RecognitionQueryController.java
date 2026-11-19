package controller.recognition;

import common.response.PageResult;
import common.response.Result;
import dto.recognition.RecognitionQueryRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import service.recognition.RecognitionQueryService;
import vo.recognition.RecognitionView;
import java.io.IOException;
import java.nio.file.Files;

/** 查询入口不提供修改、删除或重新判定，避免影响告警、申诉和评分的数据来源。 */
@RestController
@RequestMapping("/api/recognitions")
@RequiredArgsConstructor
public class RecognitionQueryController {
    private final RecognitionQueryService service;
    @GetMapping public Result<PageResult<RecognitionView>> list(@Valid @ModelAttribute RecognitionQueryRequest request) {
        return Result.success(service.list(request));
    }
    @GetMapping("/{id}") public Result<RecognitionView> detail(@PathVariable long id) { return Result.success(service.detail(id)); }
    @GetMapping("/{id}/key-frame") public ResponseEntity<Resource> keyFrame(@PathVariable long id, HttpServletRequest request) throws IOException {
        var path = service.keyFrame(id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).contentLength(Files.size(path)).cacheControl(CacheControl.noStore())
                .header("Content-Disposition", ContentDisposition.inline().filename("recognition-" + id + ".jpg").build().toString())
                .body("HEAD".equals(request.getMethod()) ? null : new FileSystemResource(path));
    }
}
