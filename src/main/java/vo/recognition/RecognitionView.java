package vo.recognition;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import service.alert.rule.RecognitionDecisionReason;

import java.time.LocalDateTime;
import java.util.List;

/** 展示识别当时保存的结果，不用申诉结论改写原始预测。 */
@Data
public class RecognitionView {
    private Long id;
    private Long cameraId;
    private Long vehicleId;
    private String plateNo;
    private Long driverId;
    private String driverName;
    private Long fleetId;
    private String fleetName;
    private String nameSource;
    private LocalDateTime capturedAt;
    private Integer pred;
    private String behaviorCode;
    private Double maxP;
    private Double energy;
    private Double knnDist;
    private Boolean ood;
    private Boolean participated;
    private String decisionReason;
    private String effectiveStatus;
    private Double speedKmh;
    private String speedStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @JsonIgnore private String keyFramePath;
    private String keyFrameUrl;
    private List<RecognitionAlertView> linkedAlerts = List.of();
    private List<RecognitionAppealView> latestAppeals = List.of();

    public String getDecisionReasonLabel() {
        return RecognitionDecisionReason.labelFor(decisionReason);
    }
}
