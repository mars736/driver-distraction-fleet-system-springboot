package controller.camera;

import common.response.Result;
import dto.camera.CameraSettings;
import dto.camera.CameraSampleIntervalRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import service.camera.CameraService;
import service.camera.CameraSourceCatalog;

import java.util.List;

@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {
    private final CameraService cameraService;

    @GetMapping
    public Result<List<CameraService.View>> list() {
        return Result.success(cameraService.listAll());
    }

    @GetMapping("/sources")
    public Result<List<CameraSourceCatalog.SourceOption>> sources() {
        return Result.success(cameraService.listSources());
    }

    @GetMapping("/{id}")
    public Result<CameraService.View> get(@PathVariable Long id) {
        return Result.success(cameraService.getById(id));
    }

    @GetMapping("/{id}/recent-frames")
    public ResponseEntity<Result<List<CameraService.RecentFrame>>> recentFrames(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(cameraService.recentFrames(id)));
    }

    @GetMapping("/{id}/recent-frames/{frameId}")
    public ResponseEntity<byte[]> recentFrame(@PathVariable long id, @PathVariable long frameId,
                                              @RequestParam String streamId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG).body(cameraService.recentFrame(id, streamId, frameId));
    }

    @PostMapping
    public Result<CameraService.View> create(@Valid @RequestBody CameraSettings camera) {
        return Result.success(cameraService.create(camera));
    }

    @PutMapping("/{id}")
    public Result<CameraService.View> update(@PathVariable Long id, @Valid @RequestBody CameraSettings camera) {
        return Result.success(cameraService.update(id, camera));
    }

    @PatchMapping("/{id}/sample-interval")
    public Result<CameraService.View> updateSampleInterval(@PathVariable long id,
            @Valid @RequestBody CameraSampleIntervalRequest request) {
        return Result.success(cameraService.updateSampleInterval(id, request.sampleIntervalSeconds(), request.configVersion()));
    }

    @PatchMapping("/{id}/enabled")
    public Result<Void> updateEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        cameraService.updateEnabled(id, enabled);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        cameraService.delete(id);
        return Result.success();
    }
}
