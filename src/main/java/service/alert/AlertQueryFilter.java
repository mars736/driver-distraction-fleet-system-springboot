package service.alert;

import service.alert.rule.AlertKind;
import java.time.LocalDateTime;

/** ownerDriverId 只由服务端登录信息赋值，不接受请求参数。 */
public record AlertQueryFilter(Long fleetId, Long vehicleId, Long driverId, Long ownerDriverId,
                               String behaviorCode, AlertKind recordType, AlertStatus status,
                               LocalDateTime startTime, LocalDateTime endTime) { }
