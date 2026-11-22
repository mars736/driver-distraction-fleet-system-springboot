package controller.telemetry;

import common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import service.telemetry.VehicleTrackService;
import vo.telemetry.VehicleTrack;

@RestController
@RequiredArgsConstructor
public class VehicleTrackController {
    private final VehicleTrackService service;

    @GetMapping("/api/vehicles/{id}/track")
    public Result<VehicleTrack> track(@PathVariable long id, @RequestParam long startTime, @RequestParam long endTime,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "500") int pageSize) {
        return Result.success(service.track(id, startTime, endTime, page, pageSize));
    }
}
