package controller.operations;

import common.response.Result;
import dto.operations.FleetCare.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import service.operations.FleetCareService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/operations/fleet")
@RequiredArgsConstructor
public class FleetCareController {
    private final FleetCareService service;

    @GetMapping("/vehicles/{id}/odometer")
    public Result<Odometer> odometer(@PathVariable long id) { return Result.success(service.odometer(id)); }

    @PutMapping("/vehicles/{id}/odometer")
    public Result<Odometer> saveOdometer(@PathVariable long id, @Valid @RequestBody OdometerRequest request) {
        return Result.success(service.saveOdometer(id, request));
    }

    @GetMapping("/vehicles/{id}/maintenance")
    public Result<List<Plan>> plans(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.plans(id, page, pageSize));
    }

    @PostMapping("/vehicles/{id}/maintenance")
    public Result<Plan> createPlan(@PathVariable long id, @Valid @RequestBody PlanRequest request) {
        return Result.success(service.createPlan(id, request));
    }

    @PostMapping("/maintenance/{id}/complete")
    public Result<Plan> completePlan(@PathVariable long id, @Valid @RequestBody ServiceCompletion request) {
        return Result.success(service.completePlan(id, request));
    }

    @PostMapping("/vehicles/{id}/repairs")
    public Result<Repair> repair(@PathVariable long id, @Valid @RequestBody RepairRequest request) {
        return Result.success(service.reportRepair(id, request));
    }

    @GetMapping("/vehicles/{id}/repairs")
    public Result<List<Repair>> repairs(@PathVariable long id, @RequestParam(required = false) RepairStatus status,
                                        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.repairs(id, status, page, pageSize));
    }

    @PostMapping("/repairs/{id}/transition")
    public Result<Repair> transition(@PathVariable long id, @Valid @RequestBody RepairTransition request) {
        return Result.success(service.transitionRepair(id, request));
    }

    @PostMapping("/vehicles/{id}/fuel")
    public Result<Long> createFuel(@PathVariable long id, @Valid @RequestBody FuelRequest request) {
        return Result.success(service.createFuel(id, request));
    }

    @PutMapping("/fuel/{id}")
    public Result<Long> updateFuel(@PathVariable long id, @Valid @RequestBody FuelRequest request) {
        return Result.success(service.updateFuel(id, request));
    }

    @GetMapping("/vehicles/{id}/fuel")
    public Result<FuelOverview> fuel(@PathVariable long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.fuel(id, start, end, page, pageSize));
    }
}
