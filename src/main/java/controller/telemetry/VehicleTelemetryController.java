package controller.telemetry;

import common.response.Result;
import common.security.UserContext;
import dto.telemetry.VehicleTelemetryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import service.telemetry.VehicleTelemetryService;

@RestController
@RequestMapping("/api/vehicle-telemetry")
@RequiredArgsConstructor
public class VehicleTelemetryController {
    private final VehicleTelemetryService telemetry;

    @PostMapping
    public Result<Void> report(@Valid @RequestBody VehicleTelemetryRequest request) {
        telemetry.report(UserContext.get().userId(), request);
        return Result.success();
    }
}
