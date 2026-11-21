package controller.dashboard;

import common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import service.dashboard.DashboardService;
import vo.dashboard.DashboardView.*;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardService service;

    @GetMapping("/overview")
    public Result<Overview> overview(@RequestParam(required = false) String fleetId) {
        return Result.success(service.overview(fleetId));
    }

    @GetMapping("/vehicles/{id}")
    public Result<VehicleDaily> vehicle(@PathVariable long id) {
        return Result.success(service.vehicle(id));
    }

    @GetMapping("/me")
    public Result<DriverDaily> me() {
        return Result.success(service.me());
    }
}
