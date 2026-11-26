package dto.camera;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 编辑时省略视频源或采样间隔可保留原配置。 */
public record CameraSettings(@Positive Long vehicleId, @Positive Long driverId,
                             @Size(max = 64) String sourceId, @Size(max = 500) String streamUrl,
                             @DecimalMin("0.2") @DecimalMax("60") Double sampleIntervalSeconds) { }
