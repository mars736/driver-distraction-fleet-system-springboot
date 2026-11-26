-- 停运起点用 Unix 毫秒。历史停运车队从迁移时刻开始计数。
SET @fleet_stopped_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='fleet' AND column_name='stopped_at'),
    'SELECT 1', 'ALTER TABLE fleet ADD COLUMN stopped_at BIGINT NULL'
);
PREPARE fleet_stopped_stmt FROM @fleet_stopped_ddl;
EXECUTE fleet_stopped_stmt;
DEALLOCATE PREPARE fleet_stopped_stmt;

SET @telemetry_received_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='vehicle_telemetry' AND column_name='received_at'),
    'SELECT 1', 'ALTER TABLE vehicle_telemetry ADD COLUMN received_at BIGINT NULL'
);
PREPARE telemetry_received_stmt FROM @telemetry_received_ddl;
EXECUTE telemetry_received_stmt;
DEALLOCATE PREPARE telemetry_received_stmt;

UPDATE fleet SET stopped_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
WHERE enabled=0 AND stopped_at IS NULL;

CREATE TABLE IF NOT EXISTS vehicle_task_offline_notice (
    task_id BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    last_sampled_at BIGINT NOT NULL,
    detected_at BIGINT NOT NULL,
    PRIMARY KEY (task_id, vehicle_id, driver_id, last_sampled_at)
) COMMENT '任务内车辆定位离线提示去重';

CREATE TABLE IF NOT EXISTS vehicle_stopped_motion_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL,
    fleet_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    stopped_at BIGINT NOT NULL,
    first_sampled_at BIGINT NOT NULL,
    second_sampled_at BIGINT NOT NULL,
    detected_at BIGINT NOT NULL,
    UNIQUE KEY uk_stopped_motion_samples (vehicle_id, driver_id, stopped_at, first_sampled_at, second_sampled_at)
) COMMENT '停运车队车辆行驶核查证据';

CREATE TABLE IF NOT EXISTS vehicle_stopped_motion_state (
    vehicle_id BIGINT PRIMARY KEY,
    fleet_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    stopped_at BIGINT NOT NULL,
    last_sampled_at BIGINT NOT NULL,
    above_threshold BOOLEAN NOT NULL,
    check_id BIGINT NULL
) COMMENT '停运车辆连续车速状态';
