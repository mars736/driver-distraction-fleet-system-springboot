CREATE TABLE IF NOT EXISTS alert_video_evidence (
    alert_id BIGINT PRIMARY KEY,
    camera_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    stream_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    captured_at_ms BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    actual_start_ms BIGINT NULL,
    actual_end_ms BIGINT NULL,
    byte_size INT NULL,
    next_attempt_ms BIGINT NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(80) NULL,
    KEY idx_video_evidence_due (status, next_attempt_ms, alert_id)
) COMMENT '告警视频证据及独立归档进度';
