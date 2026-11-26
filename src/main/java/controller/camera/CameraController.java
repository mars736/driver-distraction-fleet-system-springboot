package controller.camera;

import common.response.Result;
import dto.camera.CameraSettings;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @PostMapping
    public Result<CameraService.View> create(@Valid @RequestBody CameraSettings camera) {
        return Result.success(cameraService.create(camera));
    }

    @PutMapping("/{id}")
    public Result<CameraService.View> update(@PathVariable Long id, @Valid @RequestBody CameraSettings camera) {
        return Result.success(cameraService.update(id, camera));
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
