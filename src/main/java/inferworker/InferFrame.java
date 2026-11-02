package inferworker;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一帧待推理的图片，带归属信息（用于推理结果回写到正确的车/司机）。
 */
@Data
@AllArgsConstructor
public class InferFrame {

    /** 全局自增的帧序号，便于追踪 */
    private long frameId;

    /** 摄像头 ID */
    private Long cameraId;

    /** 车辆 ID */
    private Long vehicleId;

    /** 司机 ID */
    private Long driverId;

    /** 抽帧时间戳（epoch 毫秒） */
    private long capturedAt;

    /** JPEG 字节 */
    private byte[] jpeg;
}
