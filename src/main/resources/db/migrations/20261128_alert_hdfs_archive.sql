SET @alert_archive_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='alert_record' AND column_name='archive_revision'),
    'SELECT 1', 'ALTER TABLE alert_record ADD COLUMN archive_revision BIGINT NOT NULL DEFAULT 0'
);
PREPARE alert_archive_stmt FROM @alert_archive_ddl;
EXECUTE alert_archive_stmt;
DEALLOCATE PREPARE alert_archive_stmt;

CREATE TABLE IF NOT EXISTS alert_archive_batch (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    partition_date DATE NOT NULL,
    next_attempt_at DATETIME(3) NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    synced_at DATETIME(3) NULL,
    last_error VARCHAR(255) NULL,
    KEY idx_alert_archive_due (synced_at, next_attempt_at, id)
) COMMENT '待写入 HDFS 的不可变归档批次';

CREATE TABLE IF NOT EXISTS alert_archive_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    recognition_id BIGINT NOT NULL,
    driver_id BIGINT NULL,
    vehicle_id BIGINT NOT NULL,
    fleet_id BIGINT NULL,
    camera_id BIGINT NULL,
    captured_at DATETIME(3) NOT NULL,
    record_type VARCHAR(32) NOT NULL,
    behavior_code VARCHAR(8) NULL,
    status VARCHAR(16) NOT NULL,
    source VARCHAR(20) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    UNIQUE KEY uk_alert_archive_version (alert_id, revision),
    KEY idx_alert_archive_events_batch (batch_id, id)
) COMMENT '用于 HDFS 归档的告警状态事件';

CREATE TABLE IF NOT EXISTS alert_archive_backfill (
    id TINYINT PRIMARY KEY,
    through_id BIGINT NOT NULL,
    last_id BIGINT NOT NULL DEFAULT 0
) COMMENT '历史告警归档回填进度';
