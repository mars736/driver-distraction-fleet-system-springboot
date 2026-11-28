package controller.operations;

import common.response.PageResult;
import common.response.Result;
import dto.operations.TransportOrders.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import service.operations.TransportOrderConflict;
import service.operations.TransportOrderService;

@RestController
@RequestMapping("/api/operations/transport-orders")
@RequiredArgsConstructor
public class TransportOrderController {
    private final TransportOrderService service;

    @PostMapping
    public Result<OrderRow> create(@Valid @RequestBody Draft input) {
        return Result.success(service.create(input));
    }

    @GetMapping
    public Result<PageResult<OrderRow>> list(@RequestParam(required = false) Long fleetId,
            @RequestParam(required = false) Status status, @RequestParam(required = false) Requirement requirement,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.list(fleetId, status, requirement, page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<TransportOrderDetail> detail(@PathVariable long id) {
        return Result.success(service.detail(id));
    }

    @PutMapping("/{id}")
    public Result<OrderRow> update(@PathVariable long id, @Valid @RequestBody Edit input) {
        return Result.success(service.update(id, input));
    }

    @GetMapping("/{id}/candidates")
    public Result<PageResult<TransportOrderCandidate>> candidates(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.candidates(id, page, pageSize));
    }

    @PostMapping("/{id}/assign")
    public Result<DispatchReceipt> assign(@PathVariable long id, @Valid @RequestBody TransportOrderAssignment input,
            @RequestHeader("Idempotency-Key") String requestKey) {
        return Result.success(service.assign(id, input, requestKey));
    }

    @PostMapping("/{id}/cancel")
    public Result<OrderRow> cancel(@PathVariable long id, @Valid @RequestBody Cancel input) {
        return Result.success(service.cancel(id, input));
    }

    @ExceptionHandler(TransportOrderConflict.class)
    public ResponseEntity<Result<TransportOrderConflictDetails>> conflict(TransportOrderConflict exception) {
        return ResponseEntity.status(409).body(new Result<>(409, exception.getMessage(),
                new TransportOrderConflictDetails(exception.reason())));
    }

    public record TransportOrderConflictDetails(ConflictReason reason) { }
}
