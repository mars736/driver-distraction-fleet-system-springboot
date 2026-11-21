package service.alert.live;

import entity.AlertRecord;

import java.time.LocalDateTime;

/** 仅发送用于刷新告警列表的字段。 */
public record AlertCreatedEvent(long id, Long driverId, Long vehicleId, Long fleetId, LocalDateTime capturedAt,
                                String recordType, String behaviorCode) {
    public static AlertCreatedEvent from(AlertRecord alert) {
        return new AlertCreatedEvent(alert.getId(), alert.getDriverId(), alert.getVehicleId(), alert.getFleetId(),
                alert.getCapturedAt(), alert.getRecordType(), alert.getBehaviorCode());
    }
}
