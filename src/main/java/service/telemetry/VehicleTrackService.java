package service.telemetry;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.security.UserContext;
import common.util.GeoDistance;
import lombok.RequiredArgsConstructor;
import mapper.VehicleMapper;
import mapper.VehicleTelemetryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vo.telemetry.VehiclePosition;
import vo.telemetry.VehicleTrack;

import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class VehicleTrackService {
    private static final int MAX_POINTS = 20_000;
    private static final long MAX_RANGE_MS = 86_400_000;
    private static final long MAX_GAP_MS = 30_000;
    private static final double MAX_IMPLIED_SPEED_KMH = 200;

    private final VehicleMapper vehicles;
    private final VehicleTelemetryMapper telemetry;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VehicleTrack track(long vehicleId, long start, long end, int page, int pageSize, Long snapshotId) {
        if (!CommonConstant.ROLE_PLATFORM_ADMIN.equals(UserContext.get().role())) {
            throw new BusinessException(403, "仅管理员可查询车辆轨迹");
        }
        if (vehicleId < 1 || start < 0 || end <= start || end - start > MAX_RANGE_MS
                || page < 1 || pageSize < 1 || pageSize > 1000 || snapshotId != null && snapshotId < 0) {
            throw new BusinessException(400, "轨迹范围最多 24 小时，分页条件需有效");
        }
        if (vehicles.selectById(vehicleId) == null) { throw new BusinessException(404, "车辆不存在"); }
        long latest = telemetry.trackWatermark(vehicleId);
        if (snapshotId != null && snapshotId > latest) {
            throw new BusinessException(400, "轨迹快照无效，请从第一页重新查询");
        }
        long watermark = snapshotId == null ? latest : snapshotId;
        var positions = telemetry.findTrack(vehicleId, start, end, watermark, MAX_POINTS + 1);
        if (positions.size() > MAX_POINTS) { throw new BusinessException(422, "轨迹点过多，请缩小时间范围"); }

        long from = (page - 1L) * pageSize;
        long to = from + pageSize;
        var points = new ArrayList<VehicleTrack.Point>();
        double distanceKm = 0;
        int segment = 0;
        VehiclePosition previous = null;
        for (int i = 0; i < positions.size(); i++) {
            VehiclePosition current = positions.get(i);
            double leg = previous == null ? -1 : legDistance(previous, current);
            if (leg < 0) { segment++; leg = 0; }
            distanceKm += leg;
            if (i >= from && i < to) {
                points.add(new VehicleTrack.Point(current.driverId(), current.sampledAt(), current.latitude(),
                        current.longitude(), current.speedKmh(), segment, leg, current.coordinateSystem()));
            }
            previous = current;
        }
        var systems = positions.stream().map(VehiclePosition::coordinateSystem).distinct().limit(2).toList();
        String system = systems.isEmpty() ? null : systems.size() == 1 ? systems.getFirst() : "MIXED";
        return new VehicleTrack(vehicleId, start, end, positions.size(), distanceKm, segment, page, pageSize,
                watermark, system, points);
    }

    private static double legDistance(VehiclePosition from, VehiclePosition to) {
        long millis = to.sampledAt() - from.sampledAt();
        if (!from.driverId().equals(to.driverId()) || !from.coordinateSystem().equals(to.coordinateSystem()) || millis <= 0 || millis > MAX_GAP_MS) { return -1; }
        double distance = GeoDistance.kilometers(from.latitude(), from.longitude(), to.latitude(), to.longitude());
        return distance * 3_600_000 / millis <= MAX_IMPLIED_SPEED_KMH ? distance : -1;
    }
}
