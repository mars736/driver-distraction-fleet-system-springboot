package entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 摄像头实体，对应数据库表 camera。
 */
@Data
public class Camera {

    /** 主键，自增 */
    private Long id;

    /** 所属车辆 ID（可空） */
    private Long vehicleId;

    /** 当前司机 ID（可空） */
    private Long driverId;

    /** 视频源地址（RTSP / HTTP MJPEG） */
    private String streamUrl;

    /** 采样间隔（秒） */
    private Double sampleIntervalSeconds;

    /** 是否启用监控 */
    private Boolean enabled;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
