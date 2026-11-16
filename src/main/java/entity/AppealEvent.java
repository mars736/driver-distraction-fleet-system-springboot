package entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 每次提交、补充、撤回、上传和复核都追加事件，不覆盖原始理由。 */
@Data
public class AppealEvent {
    private Long id;
    private Long appealId;
    private String action;
    private String content;
    private Long operatorId;
    private String operatorUsername;
    private String operatorName;
    private String operatorRole;
    private Long alertReviewId; // 复核告警时，指向同一次事务生成的告警处理历史。
    private Long materialId;    // 上传材料时，指向本次新增材料；其他事件可为空。
    private LocalDateTime operatedAt;
}
