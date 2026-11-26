package controller.report;

import common.response.PageResult;
import common.response.Result;
import dto.report.ReportRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import service.report.ReportJobService;
import service.report.ReportJobService.JobView;

import java.util.Map;

@RestController
@RequestMapping("/api/report-jobs")
@RequiredArgsConstructor
public class ReportJobController {
    private final ReportJobService reports;

    @PostMapping
    public ResponseEntity<Result<JobView>> create(@Valid @RequestBody ReportRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String requestKey) {
        return ResponseEntity.accepted().body(Result.success(reports.create(request, requestKey)));
    }

    @ExceptionHandler(ReportJobService.RequestConflict.class)
    public ResponseEntity<Result<Map<String, String>>> requestConflict(ReportJobService.RequestConflict exception) {
        return ResponseEntity.status(exception.getCode()).body(new Result<>(exception.getCode(), exception.getMessage(),
                Map.of("reason", "IDEMPOTENCY_KEY_REUSED")));
    }

    @GetMapping
    public Result<PageResult<JobView>> list(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(reports.list(page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<JobView> detail(@PathVariable long id) { return Result.success(reports.detail(id)); }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        var download = reports.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.format().mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.name()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(download.file()));
    }
}
