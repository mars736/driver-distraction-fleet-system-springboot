package vo.score;

import lombok.Data;
import java.time.LocalDateTime;

/** 一条告警只出现一次。REVOKED 的原记录保留展示，但有效扣分为 0。 */
@Data
public class ScoreRecordView {
    private Long alertId;
    private Long recognitionId;
    private Long vehicleId;
    private Long fleetId;
    private LocalDateTime capturedAt;
    private String recordType;
    private String behaviorCode;
    private String status;
    private int originalDeduction;
    private int effectiveDeduction;
}
