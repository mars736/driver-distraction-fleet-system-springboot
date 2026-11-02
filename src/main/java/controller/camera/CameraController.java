package controller.camera;

import service.camera.CameraService;
import common.response.Result;
import entity.Camera;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 摄像头 REST 接口。
 */
@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final CameraService cameraService;

    @GetMapping
    public Result<List<Camera>> list() {
        return Result.success(cameraService.listAll());
    }

    @GetMapping("/{id}")
    public Result<Camera> get(@PathVariable Long id) {
        return Result.success(cameraService.getById(id));
    }

    @PostMapping
    public Result<Camera> create(@RequestBody Camera camera) {
        return Result.success(cameraService.create(camera));
    }

    @PutMapping("/{id}")
    public Result<Camera> update(@PathVariable Long id, @RequestBody Camera camera) {
        camera.setId(id);
        return Result.success(cameraService.update(camera));
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
