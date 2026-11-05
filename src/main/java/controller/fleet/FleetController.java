package controller.fleet;

import common.response.PageResult;
import common.response.Result;
import entity.Fleet;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import service.fleet.FleetService;

/**
 * 车队档案 REST 接口。
 */
@RestController
@RequestMapping("/api/fleets")
@RequiredArgsConstructor
public class FleetController {

    private final FleetService fleetService;

    @GetMapping
    public Result<PageResult<Fleet>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(fleetService.list(keyword, page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<Fleet> get(@PathVariable Long id) {
        return Result.success(fleetService.getById(id));
    }

    @PostMapping
    public Result<Fleet> create(@Valid @RequestBody Fleet fleet) {
        return Result.success(fleetService.create(fleet));
    }

    @PutMapping("/{id}")
    public Result<Fleet> update(@PathVariable Long id, @Valid @RequestBody Fleet fleet) {
        fleet.setId(id);
        return Result.success(fleetService.update(fleet));
    }

    @PatchMapping("/{id}/enabled")
    public Result<Void> updateEnabled(@PathVariable Long id, @RequestParam Boolean enabled) {
        fleetService.updateEnabled(id, enabled);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        fleetService.delete(id);
        return Result.success();
    }
}
