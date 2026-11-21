package service.telemetry;

import common.exception.BusinessException;
import dto.telemetry.VehicleTelemetryRequest;
import lombok.RequiredArgsConstructor;
import mapper.VehicleTelemetryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import service.alert.VehicleSpeedProvider;

import java.math.BigDecimal;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class VehicleTelemetryService implements VehicleSpeedProvider {
    private static final long MAX_SPEED_AGE_MS = Duration.ofSeconds(15).toMillis();
    private static final long MAX_REPORT_DELAY_MS = Duration.ofMinutes(5).toMillis();
    private static final long MAX_CLOCK_LEAD_MS = Duration.ofSeconds(5).toMillis();

    private final VehicleTelemetryMapper mapper;

    @Transactional
    public void report(long driverId, VehicleTelemetryRequest request) {
        long now = System.currentTimeMillis();
        if (request.sampledAt() < now - MAX_REPORT_DELAY_MS || request.sampledAt() > now + MAX_CLOCK_LEAD_MS) {
            throw new BusinessException(400, "定位采样时间已过期或超前，请校准设备时间");
        }
        if (mapper.lockDriverBinding(driverId, request.vehicleId()) == null) {
            throw new BusinessException(409, "车辆与当前司机的绑定不匹配");
        }

        Double speedKmh = request.speedMps() == null
                ? null : request.speedMps().multiply(BigDecimal.valueOf(3.6)).doubleValue();
        mapper.insertSample(request.vehicleId(), driverId, request.sampledAt(),
                request.latitude().doubleValue(), request.longitude().doubleValue(), speedKmh);
    }

    @Override
    public Double speedKmh(Long vehicleId, Long driverId, long capturedAt) {
        if (vehicleId == null || driverId == null) {
            return null;
        }
        // 不能用晚于识别帧的样本，也不能把前一位司机的低速用于当前司机。
        return mapper.findSpeedAt(vehicleId, driverId, capturedAt - MAX_SPEED_AGE_MS, capturedAt);
    }
}
