package dto.alert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import service.alert.AlertStatus;

@Data
public class AlertReviewRequest {
    @NotNull private AlertStatus status;
    @NotBlank @Size(max = 500) private String reason;
}
