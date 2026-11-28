package controller.alert;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.response.Result;
import dto.alert.*;
import entity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;
import service.alert.AppealMaterialStore;
import service.alert.AppealService;
import service.alert.evidence.VideoEvidenceQuery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** 前端流程：提交申诉取得 id，再用该 id 补充说明、上传材料、查看复核结果。 */
@RestController
@RequestMapping("/api/appeals")
@RequiredArgsConstructor
public class AppealController {
    private final AppealService service;
    private final AppealMaterialStore materialStore;
    private final VideoEvidenceQuery evidence;

    @PostMapping public Result<AppealRecord> create(@Valid @RequestBody AppealCreateRequest request) {
        return Result.success(service.create(request));
    }
    @GetMapping public Result<PageResult<AppealRecord>> list(@Valid @ModelAttribute AppealQueryRequest request) {
        return Result.success(service.list(request));
    }
    @GetMapping("/{id}")
    public Result<AppealRecord> detail(@PathVariable long id, Authentication authentication) {
        var appeal = service.detail(id);
        if (authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + CommonConstant.ROLE_PLATFORM_ADMIN).equals(authority.getAuthority()))) {
            appeal.setVideoEvidence(evidence.view(appeal));
        }
        return Result.success(appeal);
    }

    @GetMapping(value = "/{id}/evidence-video", produces = "video/mp4")
    public ResponseEntity<?> evidenceVideo(@PathVariable long id, HttpServletRequest request) {
        AppealRecord appeal;
        try {
            appeal = service.detail(id);
        } catch (BusinessException exception) {
            return videoError(exception.getCode(), exception.getMessage());
        }
        var item = evidence.playable(appeal);
        if (item == null) { return videoError(404, "当前申诉没有可播放的视频"); }
        try {
            byte[] body = "HEAD".equals(request.getMethod()) ? null : evidence.read(item);
            return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4")).contentLength(item.byteSize())
                    .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return videoError(503, "视频暂时无法读取，请稍后重试");
        } catch (IOException exception) {
            return videoError(503, "视频暂时无法读取，请稍后重试");
        }
    }

    private ResponseEntity<Result<Void>> videoError(int code, String message) {
        return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(Result.error(code, message));
    }
    @GetMapping("/{id}/events") public Result<List<AppealEvent>> events(@PathVariable long id) { return Result.success(service.events(id)); }
    @PostMapping("/{id}/supplements") public Result<AppealEvent> supplement(@PathVariable long id, @Valid @RequestBody AppealSupplementRequest request) {
        return Result.success(service.supplement(id, request));
    }
    @PostMapping("/{id}/withdraw") public Result<AppealRecord> withdraw(@PathVariable long id) { return Result.success(service.withdraw(id)); }
    @PostMapping("/{id}/decision") public Result<AppealRecord> decide(@PathVariable long id, @Valid @RequestBody AlertReviewRequest request) {
        return Result.success(service.decide(id, request));
    }
    @GetMapping("/{id}/materials") public Result<List<AppealMaterial>> materials(@PathVariable long id) { return Result.success(service.materials(id)); }
    @PostMapping(value = "/{id}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AppealMaterial> upload(@PathVariable long id, @RequestPart("file") MultipartFile file) throws IOException {
        return Result.success(service.upload(id, file));
    }
    @GetMapping("/{id}/materials/{materialId}")
    public ResponseEntity<Resource> download(@PathVariable long id, @PathVariable long materialId, HttpServletRequest request) throws IOException {
        // 先确认申诉归属和材料关联，再读取文件；不能拿到材料 ID 就直接下载。
        var material = service.material(id, materialId);
        var path = materialStore.read(material);
        // attachment 按附件下载，no-store 不缓存私有材料，nosniff 禁止浏览器猜内容类型。
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(material.getContentType()))
                .contentLength(Files.size(path)).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", ContentDisposition.attachment().filename(material.getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .body("HEAD".equals(request.getMethod()) ? null : new FileSystemResource(path));
    }
}
