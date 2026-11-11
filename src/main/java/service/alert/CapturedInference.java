package service.alert;

import inferworker.InferFrame;
import inferworker.InferResult;

import java.util.UUID;

/** 回调内复制的不可变快照，重试时保留同一个 sourceKey，不能使用重启会归零的 frameId 去重。 */
public record CapturedInference(String sourceKey, long frameId, Long cameraId, Long vehicleId,
                                Long driverId, long capturedAt, int pred, boolean ood,
                                double maxP, double energy, double knnDist, byte[] jpeg) {
    public CapturedInference {
        jpeg = jpeg == null ? null : jpeg.clone();
    }

    @Override
    public byte[] jpeg() { return jpeg == null ? null : jpeg.clone(); }

    public static CapturedInference copyOf(InferFrame frame, InferResult result) {
        return new CapturedInference(UUID.randomUUID().toString(), frame.getFrameId(),
                frame.getCameraId(), frame.getVehicleId(), frame.getDriverId(), frame.getCapturedAt(),
                result.getPred(), result.isOod(), result.getMaxP(), result.getEnergy(),
                result.getKnnDist(), frame.getJpeg());
    }
}
