package controller.score;

import common.response.*;
import dto.score.ScoreQueryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import service.score.DriverScoreService;
import vo.score.*;

/** 评分仅提供查询，客户端不能传入分数、扣分量或“回补金额”来改分。 */
@RestController
@RequestMapping("/api/scores")
@RequiredArgsConstructor
public class DriverScoreController {
    private final DriverScoreService service;

    @GetMapping("/me")
    public Result<DriverScoreView> mine(@Valid @ModelAttribute ScoreQueryRequest request) {
        return Result.success(service.mine(request));
    }
    @GetMapping("/drivers/{driverId}")
    public Result<DriverScoreView> summary(@PathVariable long driverId, @Valid @ModelAttribute ScoreQueryRequest request) {
        return Result.success(service.summary(driverId, request));
    }
    @GetMapping("/drivers/{driverId}/records")
    public Result<PageResult<ScoreRecordView>> records(@PathVariable long driverId, @Valid @ModelAttribute ScoreQueryRequest request) {
        return Result.success(service.records(driverId, request));
    }
    @GetMapping("/drivers/{driverId}/detail")
    public Result<DriverScoreService.Detail> detail(@PathVariable long driverId, @Valid @ModelAttribute ScoreQueryRequest request) {
        return Result.success(service.detail(driverId, request));
    }
    @GetMapping("/ranking")
    public Result<PageResult<DriverScoreView>> ranking(@Valid @ModelAttribute ScoreQueryRequest request) {
        return Result.success(service.ranking(request));
    }
}
