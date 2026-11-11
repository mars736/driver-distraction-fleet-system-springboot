package service.alert;

/** 返回采集时刻对应的 km/h；null 表示未知。真实速度接入时必须处理上报超时。 */
@FunctionalInterface
public interface VehicleSpeedProvider {
    Double speedKmh(Long vehicleId, long capturedAt);
}
