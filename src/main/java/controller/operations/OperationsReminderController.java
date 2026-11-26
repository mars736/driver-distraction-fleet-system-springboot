package controller.operations;

import common.response.Result;
import dto.operations.OperationsReminder.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import service.operations.OperationsReminderService;

import java.util.List;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationsReminderController {
    private final OperationsReminderService service;

    @PutMapping("/certificates")
    public Result<Certificate> save(@Valid @RequestBody CertificateRequest request) {
        return Result.success(service.save(request));
    }

    @GetMapping("/certificates")
    public Result<List<Certificate>> certificates(@RequestParam SubjectType subjectType, @RequestParam long subjectId) {
        return Result.success(service.certificates(subjectType, subjectId));
    }

    @GetMapping("/reminders")
    public Result<List<Reminder>> reminders(@Valid @ModelAttribute ReminderFilter filter,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.reminders(filter, page, pageSize));
    }
}
