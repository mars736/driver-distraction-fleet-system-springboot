package dto.alert;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class AppealSupplementRequest {
    @NotBlank @Size(max = 1000) private String content;
}
