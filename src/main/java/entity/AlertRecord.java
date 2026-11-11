package entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AlertRecord {
    private Long id;
    private Long recognitionId;
    private Long cameraId;
    private Long vehicleId;
    private Long driverId;
    private Long fleetId;
    private LocalDateTime capturedAt;
    private String behaviorCode;
    private Double maxP;
    private String recordType;
    private Integer eventCount;
    private String smsStatus;
    private String status;
    private String keyFramePath;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
