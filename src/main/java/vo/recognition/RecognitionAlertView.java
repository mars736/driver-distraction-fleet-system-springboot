package vo.recognition;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class RecognitionAlertView {
    @JsonIgnore private Long recognitionId;
    private Long id;
    private String recordType;
    private String behaviorCode;
    private String status;
    private String smsStatus;
}
