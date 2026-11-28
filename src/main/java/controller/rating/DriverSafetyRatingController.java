package controller.rating;

import common.exception.BusinessException;
import common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import service.rating.DriverSafetyRating.SafetyRatingBatchView;
import service.rating.DriverSafetyRatingStore;

import java.util.UUID;

@RestController
@RequestMapping("/api/operations/driver-safety-rating/batches")
@RequiredArgsConstructor
public class DriverSafetyRatingController {
    private final DriverSafetyRatingStore ratings;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<SafetyRatingBatchView> start() {
        return Result.success(ratings.manual().view());
    }

    @GetMapping("/{id}")
    public Result<SafetyRatingBatchView> get(@PathVariable UUID id) {
        return Result.success(ratings.get(id.toString()).view());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> failure(BusinessException ex) {
        return ResponseEntity.status(ex.getCode()).body(Result.error(ex.getCode(), ex.getMessage()));
    }
}
