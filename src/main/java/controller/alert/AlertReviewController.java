package controller.alert;

import common.response.Result;
import dto.alert.AlertReviewRequest;
import entity.AlertReviewRecord;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import service.alert.AlertReviewService;
import java.util.List;

@RestController
@RequestMapping("/api/alerts/{id}/reviews")
@RequiredArgsConstructor
public class AlertReviewController {
    private final AlertReviewService service;

    @PostMapping
    public Result<AlertReviewRecord> review(@PathVariable long id, @Valid @RequestBody AlertReviewRequest request) {
        return Result.success(service.review(id, request));
    }

    @GetMapping
    public Result<List<AlertReviewRecord>> history(@PathVariable long id) {
        return Result.success(service.history(id));
    }
}
