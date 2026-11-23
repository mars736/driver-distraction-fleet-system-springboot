package service.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.security.UserContext;
import lombok.RequiredArgsConstructor;
import mapper.DashboardMapper;
import mapper.FleetMapper;
import mapper.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.alert.rule.VehicleAlertState;
import vo.dashboard.DashboardView.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DashboardService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final DashboardMapper mapper;
    private final FleetMapper fleets;
    private final VehicleMapper vehicles;
    private final ObjectMapper json;

    public Overview overview(String requestedFleetId) {
        requireRole(CommonConstant.ROLE_PLATFORM_ADMIN);
        Long fleetId = parseFleetId(requestedFleetId);
        if (fleetId != null) {
            if (fleets.selectById(fleetId) == null) { throw new BusinessException(404, "车队不存在"); }
        }
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        var scope = daily(today, fleetId, null, null);
        return new Overview(today, fleetId, mapper.fleetSize(fleetId), recognitions(scope), mapper.alertCounts(scope));
    }

    public FleetRange range(Long fleetId, LocalDateTime start, LocalDateTime end) {
        requireRole(CommonConstant.ROLE_PLATFORM_ADMIN);
        var fleet = fleetId == null ? null : fleets.selectById(fleetId);
        if (fleetId != null && fleet == null) { throw new BusinessException(404, "车队不存在"); }
        var scope = new Scope(start, end, fleetId, null, null);
        return new FleetRange(fleetId, fleet == null ? "全部车队" : fleet.getName(), start, end,
                mapper.fleetSize(fleetId), recognitions(scope), mapper.alertCounts(scope));
    }

    public VehicleDaily vehicle(long vehicleId) {
        requireRole(CommonConstant.ROLE_PLATFORM_ADMIN);
        requirePositive(vehicleId);
        var vehicle = vehicles.selectById(vehicleId);
        if (vehicle == null) { throw new BusinessException(404, "车辆不存在"); }
        var now = ZonedDateTime.now(BUSINESS_ZONE);
        var today = now.toLocalDate();
        var scope = daily(today, null, vehicleId, null);
        return new VehicleDaily(today, vehicleId, vehicle.getPlateNo(), vehicle.getFleetId(),
                recognitions(scope), mapper.alertCounts(scope), currentHourEvents(vehicleId, now));
    }

    public DriverDaily me() {
        long driverId = requireRole(CommonConstant.ROLE_DRIVER);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        // 始终按采集时保存的司机 ID 汇总，换车后仍可查看自己的历史。
        var scope = daily(today, null, null, driverId);
        return new DriverDaily(today, driverId, recognitions(scope), mapper.alertCounts(scope), mapper.recentAlerts(driverId));
    }

    private Recognitions recognitions(Scope scope) {
        List<Bucket> buckets = mapper.recognitionBuckets(scope);
        long normal = count(buckets, CommonConstant.BEHAVIOR_C0);
        long rejected = count(buckets, "OOD");
        long unknown = count(buckets, "UNKNOWN");
        long total = buckets.stream().mapToLong(Bucket::frameCount).sum();
        long distracted = total - normal - rejected - unknown;
        var behaviors = buckets.stream()
                .filter(bucket -> bucket.category().startsWith("c") && !bucket.category().equals(CommonConstant.BEHAVIOR_C0))
                .map(bucket -> new Behavior(bucket.category(), bucket.frameCount(), share(bucket.frameCount(), distracted)))
                .toList();
        return new Recognitions(total, normal, distracted, rejected, unknown, share(rejected, total), behaviors);
    }

    private int currentHourEvents(long vehicleId, ZonedDateTime now) {
        String stateJson = mapper.vehicleAlertState(vehicleId);
        if (stateJson == null) { return 0; }
        long hourStart = now.truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();
        try {
            VehicleAlertState state = json.readValue(stateJson, VehicleAlertState.class);
            return state != null && state.getHourStart() == hourStart
                    && state.getLastCapturedAt() <= now.toInstant().toEpochMilli() ? state.getOtherEvents() : 0;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("车辆当前小时计数格式无效", e);
        }
    }

    private static Scope daily(LocalDate date, Long fleetId, Long vehicleId, Long driverId) {
        return new Scope(date.atStartOfDay(), date.plusDays(1).atStartOfDay(), fleetId, vehicleId, driverId);
    }

    private static long count(List<Bucket> buckets, String category) {
        return buckets.stream().filter(bucket -> category.equals(bucket.category())).mapToLong(Bucket::frameCount).sum();
    }

    private static BigDecimal share(long part, long total) {
        if (total == 0) { return BigDecimal.ZERO; }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private static long requireRole(String role) {
        var user = UserContext.get();
        if (!role.equals(user.role()) || user.userId() == null) {
            throw new BusinessException(403, "无权限访问此工作台");
        }
        return user.userId();
    }

    private static void requirePositive(long id) {
        if (id <= 0) { throw new BusinessException(400, "ID 必须为正数"); }
    }

    private static Long parseFleetId(String value) {
        if (value == null) { return null; }
        try {
            long id = Long.parseLong(value);
            requirePositive(id);
            return id;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "车队 ID 必须为正整数");
        }
    }
}
