package dto.alert;

import jakarta.validation.constraints.*;
import lombok.Data;
import service.alert.AppealStatus;
import service.alert.AppealTargetType;

@Data
public class AppealQueryRequest {
    private AppealStatus status;
    private AppealTargetType targetType;
    @Positive private Long targetId;
    @Positive private Long driverId; // 管理员可筛选司机；司机填写此项也只能查询本人。
    @Min(1) private int page = 1;
    @Min(1) @Max(100) private int pageSize = 10;
}
