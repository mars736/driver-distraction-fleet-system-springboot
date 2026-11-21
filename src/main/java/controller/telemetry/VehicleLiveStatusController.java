package controller.telemetry;

import common.response.PageResult;
import common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import service.telemetry.VehicleLiveStatusService;
import vo.telemetry.VehicleLiveStatus;

@RestController
@RequestMapping("/api/vehicles/live-status")
@RequiredArgsConstructor
public class VehicleLiveStatusController {
    private final VehicleLiveStatusService service;

    @GetMapping
    public Result<PageResult<VehicleLiveStatus>> list(@RequestParam(required = false) Long fleetId,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(service.list(fleetId, page, pageSize));
    }
}
