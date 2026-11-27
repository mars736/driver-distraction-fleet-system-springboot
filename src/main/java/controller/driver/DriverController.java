package controller.driver;

import common.response.PageResult;
import common.response.Result;
import entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import service.driver.DriverEmployeeNoConflict;
import service.driver.DriverService;

/**
 * 司机档案 REST 接口。
 */
@RestController
@RequestMapping("/api/drivers")
@RequiredArgsConstructor
public class DriverController {

    private final DriverService driverService;

    @GetMapping
    public Result<PageResult<User>> list(@RequestParam(required = false) Long fleetId,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(driverService.list(fleetId, keyword, page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<User> get(@PathVariable Long id) {
        return Result.success(driverService.getById(id));
    }

    @PostMapping
    public Result<User> create(@RequestBody User user) {
        return Result.success(driverService.create(user));
    }

    @PutMapping("/{id}")
    public Result<User> update(@PathVariable Long id, @RequestBody User user) {
        user.setId(id);
        return Result.success(driverService.update(user));
    }

    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam boolean enabled) {
        driverService.updateStatus(id, enabled);
        return Result.success();
    }

    @PatchMapping("/{id}/vehicle")
    public Result<Void> updateVehicle(@PathVariable Long id,
                                      @RequestParam(required = false) Long vehicleId) {
        driverService.updateVehicle(id, vehicleId);
        return Result.success();
    }

    @ExceptionHandler(DriverEmployeeNoConflict.class)
    public ResponseEntity<Result<FieldConflict>> employeeNoConflict(DriverEmployeeNoConflict exception) {
        return ResponseEntity.status(409).body(new Result<>(409, exception.getMessage(),
                new FieldConflict("EMPLOYEE_NO_CONFLICT", "employeeNo")));
    }

    public record FieldConflict(String reason, String field) { }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        driverService.delete(id);
        return Result.success();
    }
}
