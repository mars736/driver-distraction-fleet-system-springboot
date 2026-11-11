package service.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import entity.AlertRecord;
import entity.RecognitionRecord;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import mapper.AlertDecisionMapper;
import mapper.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import service.alert.rule.AlertKind;
import service.alert.rule.AlertRuleEngine;
import service.alert.rule.VehicleAlertState;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AlertDecisionService {
    private final AlertDecisionMapper mapper;
    private final VehicleMapper vehicleMapper;
    private final VehicleSpeedProvider speedProvider;
    private final AlertRuleEngine engine;
    private final ObjectMapper objectMapper;
    private final KeyFrameStore keyFrameStore;

    /** 锁定车辆状态，识别记录、告警和计数一起提交；重试不会重复累计。 */
    @Transactional(rollbackFor = Exception.class)
    public List<AlertRecord> process(CapturedInference input, boolean continuityBroken) throws IOException {
        Vehicle vehicle = input.vehicleId() == null ? null : vehicleMapper.selectById(input.vehicleId());
        VehicleAlertState state = new VehicleAlertState();
        if (vehicle != null) {
            mapper.ensureState(vehicle.getId(), objectMapper.writeValueAsString(state));
            state = objectMapper.readValue(mapper.lockState(vehicle.getId()), VehicleAlertState.class);
        }
        RecognitionRecord existing = mapper.findRecognition(input.sourceKey());
        if (existing != null) { return mapper.pendingSms(existing.getId()); }

        Double speed = vehicle == null ? null : speedProvider.speedKmh(vehicle.getId(), input.capturedAt());
        boolean validNumbers = Double.isFinite(input.maxP()) && input.maxP() >= 0 && input.maxP() <= 1
                && Double.isFinite(input.energy()) && Double.isFinite(input.knnDist()) && input.knnDist() >= 0;
        AlertRuleEngine.Evaluation evaluation = engine.evaluate(state, new AlertRuleEngine.Observation(
                vehicle == null ? null : vehicle.getId(), input.driverId(), input.capturedAt(),
                validNumbers ? input.pred() : -1, input.ood(), speed, continuityBroken));

        String keyFrame = evaluation.hasViolation() ? keyFrameStore.save(input) : null;
        if (keyFrame != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) { keyFrameStore.delete(keyFrame); }
                }
            });
        }
        RecognitionRecord recognition = recognition(input, vehicle, evaluation, speed, validNumbers, keyFrame);
        mapper.insertRecognition(recognition);
        for (AlertKind kind : evaluation.alerts()) {
            AlertRecord alert = new AlertRecord();
            alert.setRecognitionId(recognition.getId());
            alert.setCameraId(input.cameraId());
            alert.setVehicleId(input.vehicleId());
            alert.setDriverId(input.driverId());
            alert.setFleetId(recognition.getFleetId());
            alert.setCapturedAt(recognition.getCapturedAt());
            alert.setRecordType(kind.name());
            boolean violation = kind != AlertKind.SPEED_UNKNOWN;
            alert.setBehaviorCode(violation ? recognition.getBehaviorCode() : null);
            alert.setMaxP(violation ? recognition.getMaxP() : null);
            alert.setKeyFramePath(violation ? keyFrame : null);
            alert.setEventCount(kind == AlertKind.SMS_ALERT ? evaluation.state().getSmsFrames()
                    : kind == AlertKind.BACKLOG ? evaluation.state().getOtherEvents() : 0);
            alert.setSmsStatus(kind == AlertKind.SMS_ALERT ? "PENDING" : "NOT_REQUIRED");
            mapper.insertAlert(alert);
        }
        if (vehicle != null) {
            mapper.updateState(vehicle.getId(), objectMapper.writeValueAsString(evaluation.state()));
        }
        return mapper.pendingSms(recognition.getId());
    }

    private RecognitionRecord recognition(CapturedInference input, Vehicle vehicle,
            AlertRuleEngine.Evaluation evaluation, Double speed, boolean validNumbers, String keyFrame) {
        RecognitionRecord record = new RecognitionRecord();
        record.setSourceKey(input.sourceKey());
        record.setSourceFrameId(input.frameId());
        record.setCameraId(input.cameraId());
        record.setVehicleId(input.vehicleId());
        record.setDriverId(input.driverId());
        record.setFleetId(vehicle == null ? null : vehicle.getFleetId());
        record.setCapturedAt(Instant.ofEpochMilli(input.capturedAt()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        record.setPred(input.pred());
        record.setBehaviorCode(!input.ood() && validNumbers && input.pred() >= 0 && input.pred() <= 9
                ? "c" + input.pred() : null);
        record.setMaxP(finite(input.maxP()));
        record.setEnergy(finite(input.energy()));
        record.setKnnDist(finite(input.knnDist()));
        record.setOod(input.ood());
        record.setParticipated(evaluation.participated());
        record.setDecisionReason(vehicle == null && input.vehicleId() != null
                ? "VEHICLE_NOT_FOUND" : evaluation.reason());
        record.setSpeedKmh(AlertRuleEngine.isKnownSpeed(speed) ? speed : null);
        record.setSpeedStatus(AlertRuleEngine.isKnownSpeed(speed) ? "KNOWN" : "UNKNOWN");
        record.setKeyFramePath(keyFrame);
        return record;
    }

    private Double finite(double value) { return Double.isFinite(value) ? value : null; }
}
