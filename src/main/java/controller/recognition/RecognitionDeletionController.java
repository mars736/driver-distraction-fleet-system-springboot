package controller.recognition;

import common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import service.recognition.RecognitionDeletionService;
import service.recognition.RecognitionDeletionService.DeletionResult;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/recognitions")
@RequiredArgsConstructor
public class RecognitionDeletionController {
    private final RecognitionDeletionService service;

    @DeleteMapping("/{id}")
    public Result<DeletionResult> delete(@PathVariable long id) {
        return Result.success(service.delete(Collections.singletonList(id)));
    }

    @PostMapping("/batch-delete")
    public Result<DeletionResult> deleteBatch(@RequestBody Batch request) {
        return Result.success(service.delete(request.ids()));
    }

    public record Batch(List<Long> ids) { }
}
