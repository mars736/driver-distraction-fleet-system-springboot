package entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 操作人信息保存快照，后续账号改名或删除不会改写历史记录。 */
@Data
public class AlertReviewRecord {
    private Long id;
    private Long alertId;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private Long operatorId;
    private String operatorUsername;
    private String operatorName;
    private LocalDateTime operatedAt;
}
