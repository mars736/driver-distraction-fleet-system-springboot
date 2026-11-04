package controller.vehicle;

import common.response.PageResult;
import common.response.Result;
import entity.Vehicle;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import service.vehicle.VehicleService;

/**
 * 车辆 REST 接口（M0）。
 */
@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @GetMapping
    public Result<PageResult<Vehicle>> list(@RequestParam(required = false) Long companyId,
                                            @RequestParam(defaultValue = "1") Integer page,
                                            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(vehicleService.list(companyId, page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<Vehicle> get(@PathVariable Long id) {
        return Result.success(vehicleService.getById(id));
    }

    @PostMapping
    public Result<Vehicle> create(@Valid @RequestBody Vehicle vehicle) {
        return Result.success(vehicleService.create(vehicle));
    }

    @PutMapping("/{id}")
    public Result<Vehicle> update(@PathVariable Long id, @Valid @RequestBody Vehicle vehicle) {
        vehicle.setId(id);
        return Result.success(vehicleService.update(vehicle));
    }

    @PatchMapping("/{id}/monitor-enabled")
    public Result<Void> updateMonitorEnabled(@PathVariable Long id, @RequestParam Integer monitorEnabled) {
        vehicleService.updateMonitorEnabled(id, monitorEnabled);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        vehicleService.delete(id);
        return Result.success();
    }
}
