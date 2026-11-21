package vo.recognition;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

/** 每个申诉目标只返回最近一次申诉，撤回后再次提交不会把列表联结成重复行。 */
@Data
public class RecognitionAppealView {
    @JsonIgnore private Long recognitionId;
    private Long id;
    private String targetType;
    private Long targetId;
    private String status;
    private String decisionReason;
    private LocalDateTime reviewedAt;
}
