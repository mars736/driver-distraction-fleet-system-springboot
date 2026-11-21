package dto.alert;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import service.alert.AlertStatus;
import service.alert.rule.AlertKind;

/** 列表和报表使用相同的筛选参数。 */
@Data
public class AlertFilterRequest {
    @Positive private Long fleetId;
    @Positive private Long vehicleId;
    @Positive private Long driverId;
    @Pattern(regexp = "c[0-9]") private String behaviorCode;
    private AlertKind recordType;
    private AlertStatus status;
    @Size(max = 40) private String startTime;
    @Size(max = 40) private String endTime;
}
