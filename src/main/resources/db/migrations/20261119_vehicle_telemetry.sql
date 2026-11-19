CREATE TABLE IF NOT EXISTS vehicle_telemetry (
    vehicle_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    sampled_at BIGINT NOT NULL COMMENT '手机 GPS 采样时间，Unix 毫秒',
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    speed_kmh DOUBLE NULL COMMENT '设备未取得有效速度时为空',
    PRIMARY KEY (vehicle_id, driver_id, sampled_at)
) COMMENT '司机 App 车辆定位与速度样本';
