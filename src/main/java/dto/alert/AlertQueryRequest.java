package dto.alert;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AlertQueryRequest extends AlertFilterRequest {
    @Min(1) private int page = 1;
    @Min(1) @Max(100) private int pageSize = 10;
}
