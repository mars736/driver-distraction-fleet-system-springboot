package vo.alert;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AlertView {
    private Long id;
    private Long recognitionId;
    private Long cameraId;
    private Long vehicleId;
    private String plateNo;
    private Long driverId;
    private String driverName;
    private Long fleetId;
    private String fleetName;
    private LocalDateTime capturedAt;
    private String behaviorCode;
    private Double maxP;
    private String recordType;
    private Integer eventCount;
    private String smsStatus;
    private boolean smsSimulated;
    private boolean violation;
    private String status;
    private Double speedKmh;
    private String speedStatus;
    private String keyFrameUrl;
    @JsonIgnore private String keyFramePath;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
