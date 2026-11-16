package dto.alert;

import jakarta.validation.constraints.*;
import lombok.Data;
import service.alert.AppealTargetType;

/** 司机只需要说明“对哪条记录、为什么有异议”，身份信息从登录状态取得。 */
@Data
public class AppealCreateRequest {
    @NotNull private AppealTargetType targetType;
    @NotNull @Positive private Long targetId;
    @NotBlank @Size(max = 1000) private String reason;
}
