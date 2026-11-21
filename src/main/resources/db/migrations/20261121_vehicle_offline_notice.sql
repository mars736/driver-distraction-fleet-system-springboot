CREATE TABLE IF NOT EXISTS vehicle_offline_notice (
    vehicle_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    last_sampled_at BIGINT NOT NULL,
    detected_at BIGINT NOT NULL,
    PRIMARY KEY (vehicle_id, driver_id, last_sampled_at)
) COMMENT '车辆定位离线通知去重记录';
