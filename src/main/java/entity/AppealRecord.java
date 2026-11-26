package entity;

import lombok.Data;
import service.alert.AppealStatus;
import service.alert.AppealTargetType;
import java.time.LocalDateTime;

/** 一条申诉的当前状态；每次操作的详细经过另存 AppealEvent，不覆盖历史。 */
@Data
public class AppealRecord {
    private Long id;
    private AppealTargetType targetType;
    private Long targetId;
    private Long driverId;
    private Long fleetId;
    private Long vehicleId;
    private String reason;
    private String targetStatusBefore; // 提交时原记录的状态，方便对照后续复核结果。
    private AppealStatus status;
    private String effectiveStatus;
    private String decisionReason;
    private Long reviewerId;
    private Long decisionEventId;
    private LocalDateTime reviewedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
