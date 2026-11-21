package dto.recognition;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 这里只接收筛选条件，查询归属由服务端登录信息决定。 */
@Data
public class RecognitionQueryRequest {
    @Positive private Long fleetId;
    @Positive private Long vehicleId;
    @Positive private Long driverId;
    @Positive private Long cameraId;
    @Pattern(regexp = "c[0-9]") private String behaviorCode;
    private Boolean ood;
    private Boolean participated;
    @Size(max = 40) private String startTime;
    @Size(max = 40) private String endTime;
    @Min(1) private int page = 1;
    @Min(1) @Max(100) private int pageSize = 10;
}
