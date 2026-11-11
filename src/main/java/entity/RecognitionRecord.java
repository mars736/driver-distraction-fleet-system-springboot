package entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RecognitionRecord {
    private Long id;
    private String sourceKey;
    private Long sourceFrameId;
    private Long cameraId;
    private Long vehicleId;
    private Long driverId;
    private Long fleetId;
    private LocalDateTime capturedAt;
    private Integer pred;
    private String behaviorCode;
    private Double maxP;
    private Double energy;
    private Double knnDist;
    private Boolean ood;
    private Boolean participated;
    private String decisionReason;
    private Double speedKmh;
    private String speedStatus;
    private String keyFramePath;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
