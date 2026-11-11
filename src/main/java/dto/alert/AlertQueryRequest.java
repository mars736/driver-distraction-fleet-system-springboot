package dto.alert;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import service.alert.rule.AlertKind;

@Data
public class AlertQueryRequest {
    @Positive private Long fleetId;
    @Positive private Long vehicleId;
    @Positive private Long driverId;
    @Pattern(regexp = "c[0-9]") private String behaviorCode;
    private AlertKind recordType;
    @Size(max = 40) private String startTime;
    @Size(max = 40) private String endTime;
    @Min(1) private int page = 1;
    @Min(1) @Max(100) private int pageSize = 10;
}
