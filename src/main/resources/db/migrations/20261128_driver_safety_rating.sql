CREATE TABLE IF NOT EXISTS driver_safety_rating_batch (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    status VARCHAR(12) NOT NULL,
    week_key DATE NULL,
    data_as_of DATETIME(3) NOT NULL,
    frozen_at DATETIME(3) NULL,
    published_at DATETIME(3) NULL,
    stage1_application_id VARCHAR(64) NULL,
    stage2_application_id VARCHAR(64) NULL,
    error_message VARCHAR(255) NULL,
    UNIQUE KEY uk_safety_rating_week (week_key)
) COMMENT '司机长期安全评级批次';

CREATE TABLE IF NOT EXISTS driver_safety_rating_control (
    id TINYINT PRIMARY KEY,
    active_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    published_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
) COMMENT '串行执行和原子发布长期评级';
INSERT IGNORE INTO driver_safety_rating_control(id) VALUES(1);

CREATE TABLE IF NOT EXISTS driver_safety_rating_roster (
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    driver_id BIGINT NOT NULL,
    registered_at DATETIME(3) NOT NULL,
    has_driving_history BOOLEAN NOT NULL,
    PRIMARY KEY (batch_id, driver_id)
) COMMENT '评级作业使用的司机冻结名单';

CREATE TABLE IF NOT EXISTS driver_safety_rating_input (
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    archive_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    partition_date DATE NOT NULL,
    PRIMARY KEY (batch_id, archive_id)
) COMMENT '评级作业使用的归档文件清单';

CREATE TABLE IF NOT EXISTS driver_safety_rating_result (
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    driver_id BIGINT NOT NULL,
    event_count BIGINT NOT NULL,
    score DECIMAL(4,1) NOT NULL,
    level CHAR(1) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    has_driving_history BOOLEAN NOT NULL,
    PRIMARY KEY (batch_id, driver_id)
) COMMENT '每名司机每个批次的长期评级结果';
