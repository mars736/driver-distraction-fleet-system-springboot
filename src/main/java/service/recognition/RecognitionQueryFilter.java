package service.recognition;

import java.time.LocalDateTime;

/** ownerDriverId 不出现在请求 DTO 中，前端无法用同名参数扩大权限。 */
public record RecognitionQueryFilter(Long fleetId, Long vehicleId, Long driverId, Long cameraId,
        Long ownerDriverId, String behaviorCode, Boolean ood, Boolean participated,
        LocalDateTime startTime, LocalDateTime endTime) { }
