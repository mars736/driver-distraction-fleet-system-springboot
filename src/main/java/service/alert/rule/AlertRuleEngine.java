package service.alert.rule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 不依赖 Spring、数据库或系统时钟的判定逻辑；输入时间使用采集时间。 */
public class AlertRuleEngine {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final int smsThreshold;
    private final int otherThreshold;
    private final double silentSpeedKmh;

    public AlertRuleEngine(int smsThreshold, int otherThreshold, double silentSpeedKmh) {
        if (smsThreshold < 1 || otherThreshold < 1 || !Double.isFinite(silentSpeedKmh)
                || silentSpeedKmh < 0) {
            throw new IllegalArgumentException("Invalid alert thresholds");
        }
        this.smsThreshold = smsThreshold;
        this.otherThreshold = otherThreshold;
        this.silentSpeedKmh = silentSpeedKmh;
    }

    public record Observation(Long vehicleId, Long driverId, long capturedAt, int pred,
                              boolean ood, Double speedKmh, boolean continuityBroken) { }

    public record Evaluation(VehicleAlertState state, boolean participated, String reason,
                             List<AlertKind> alerts) {
        public Evaluation { alerts = List.copyOf(alerts); }

        public boolean hasViolation() {
            return alerts.contains(AlertKind.SMS_ALERT) || alerts.contains(AlertKind.BACKLOG);
        }
    }

    public Evaluation evaluate(VehicleAlertState previous, Observation frame) {
        VehicleAlertState state = new VehicleAlertState(previous);
        if (frame.vehicleId() == null) {
            return new Evaluation(state, false, "MISSING_VEHICLE", List.of());
        }
        if (frame.capturedAt() < state.getLastCapturedAt()) {
            return new Evaluation(state, false, "OUT_OF_ORDER", List.of());
        }
        long hour = Instant.ofEpochMilli(frame.capturedAt()).atZone(ZONE)
                .truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();
        if (hour != state.getHourStart()) {
            state.setHourStart(hour);
            state.setOtherEvents(0);
            state.setOtherAlerted(false);
        }
        if (frame.continuityBroken() || !Objects.equals(frame.driverId(), state.getDriverId())) {
            state.resetContinuity();
        }
        state.setDriverId(frame.driverId());
        state.setLastCapturedAt(frame.capturedAt());

        boolean speedKnown = isKnownSpeed(frame.speedKmh());
        List<AlertKind> alerts = new ArrayList<>();
        if (!speedKnown && !state.isSpeedUnknown()) {
            alerts.add(AlertKind.SPEED_UNKNOWN);
        }
        state.setSpeedUnknown(!speedKnown);
        if (speedKnown && frame.speedKmh() <= silentSpeedKmh) {
            state.resetContinuity();
            return new Evaluation(state, false, "LOW_SPEED", alerts);
        }
        // OOD 只跳过，不清零有效帧序列。
        if (frame.ood()) {
            return new Evaluation(state, false, "OOD", alerts);
        }
        if (frame.pred() < 0 || frame.pred() > 9) {
            state.resetContinuity();
            return new Evaluation(state, false, "INVALID_PREDICTION", alerts);
        }
        if (frame.pred() == 0) {
            state.resetContinuity();
        } else if (frame.pred() == 1 || frame.pred() == 3) {
            if (state.getSmsFrames() < smsThreshold) {
                state.setSmsFrames(state.getSmsFrames() + 1);
            }
            if (state.getSmsFrames() >= smsThreshold && !state.isSmsAlerted()) {
                alerts.add(AlertKind.SMS_ALERT);
                state.setSmsAlerted(true);
            }
        } else {
            state.setSmsFrames(0);
            state.setSmsAlerted(false);
            if (!state.isOtherSegment()) {
                state.setOtherSegment(true);
                if (state.getOtherEvents() < Integer.MAX_VALUE) {
                    state.setOtherEvents(state.getOtherEvents() + 1);
                }
                if (state.getOtherEvents() > otherThreshold && !state.isOtherAlerted()) {
                    alerts.add(AlertKind.BACKLOG);
                    state.setOtherAlerted(true);
                }
            }
        }
        return new Evaluation(state, true, "EVALUATED", alerts);
    }

    public static boolean isKnownSpeed(Double speed) {
        return speed != null && Double.isFinite(speed) && speed >= 0;
    }
}
