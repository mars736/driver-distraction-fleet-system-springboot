package controller.driver;

import common.response.PageResult;
import common.response.Result;
import entity.Driver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import service.driver.DriverService;

/**
 * 司机 REST 接口（M0）。
 * 管理员不能新增司机这条业务规则属于 M1 的注册流程，本轮 controller 先提供档案维护能力。
 */
@RestController
@RequestMapping("/api/drivers")
@RequiredArgsConstructor
public class DriverController {

    private final DriverService driverService;

    @GetMapping
    public Result<PageResult<Driver>> list(@RequestParam(required = false) Long companyId,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(defaultValue = "1") Integer page,
                                           @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(driverService.list(companyId, keyword, page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<Driver> get(@PathVariable Long id) {
        return Result.success(driverService.getById(id));
    }

    @PostMapping
    public Result<Driver> create(@Valid @RequestBody Driver driver) {
        return Result.success(driverService.create(driver));
    }

    @PutMapping("/{id}")
    public Result<Driver> update(@PathVariable Long id, @Valid @RequestBody Driver driver) {
        driver.setId(id);
        return Result.success(driverService.update(driver));
    }

    @PatchMapping("/{id}/status")
    public Result<Void> updateAccountStatus(@PathVariable Long id, @RequestParam Integer accountStatus) {
        driverService.updateAccountStatus(id, accountStatus);
        return Result.success();
    }

    /** 绑定/解绑车辆：vehicleId 不传即为解绑 */
    @PatchMapping("/{id}/vehicle")
    public Result<Void> updateVehicle(@PathVariable Long id,
                                      @RequestParam(required = false) Long vehicleId) {
        driverService.updateVehicle(id, vehicleId);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        driverService.delete(id);
        return Result.success();
    }
}
