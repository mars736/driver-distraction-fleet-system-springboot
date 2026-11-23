package controller.operations;

import common.response.Result;
import dto.operations.ViolationDispatch.*;
import dto.operations.Workforce.Position;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import service.operations.ViolationDispatchService;

import java.util.List;

@RestController
@RequestMapping("/api/operations/violation-dispatch")
@RequiredArgsConstructor
public class ViolationDispatchController {
    private final ViolationDispatchService service;

    @GetMapping("/orders")
    public Result<List<WorkOrder>> list(@RequestParam(required = false) Long fleetId,
                                      @RequestParam(required = false) Long driverId,
                                      @RequestParam(required = false) Status status,
                                      @RequestParam(required = false) RiskLevel risk,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.list(fleetId, driverId, status, risk, page, pageSize));
    }

    @GetMapping("/orders/{id}")
    public Result<WorkOrder> detail(@PathVariable long id) { return Result.success(service.detail(id)); }

    @PutMapping("/operator-position")
    public Result<Void> position(@Valid @RequestBody Position position) {
        service.reportPosition(position);
        return Result.success();
    }

    @GetMapping("/orders/{id}/candidates")
    public Result<List<Candidate>> candidates(@PathVariable long id) { return Result.success(service.candidates(id)); }

    @PostMapping("/orders/{id}/assign")
    public Result<WorkOrder> assign(@PathVariable long id, @Valid @RequestBody Assign input) {
        return Result.success(service.assign(id, input.assigneeId()));
    }

    @PostMapping("/orders/{id}/assign-nearest")
    public Result<WorkOrder> nearest(@PathVariable long id) { return Result.success(service.assignNearest(id)); }

    @PostMapping("/orders/{id}/start")
    public Result<WorkOrder> start(@PathVariable long id) { return Result.success(service.start(id)); }

    @PostMapping("/orders/{id}/close")
    public Result<WorkOrder> close(@PathVariable long id, @Valid @RequestBody Close input) {
        return Result.success(service.close(id, input));
    }

    @GetMapping("/orders/{id}/personnel-recommendation")
    public Result<Recommendation> recommendation(@PathVariable long id) { return Result.success(service.recommendation(id)); }

    @PostMapping("/orders/{id}/personnel-recommendation")
    public Result<Recommendation> decidePersonnel(@PathVariable long id, @Valid @RequestBody PersonnelDecision input) {
        return Result.success(service.decidePersonnel(id, input));
    }
}
