-- 已有七张业务表的数据库只需执行本增量脚本；不删除、不改写既有告警。
CREATE TABLE IF NOT EXISTS alert_review_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    from_status VARCHAR(16) NOT NULL,
    to_status VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    operator_id BIGINT NOT NULL,
    operator_username VARCHAR(64) NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    operated_at DATETIME(3) NOT NULL COMMENT '北京时间操作时间',
    KEY idx_alert_review_time (alert_id, operated_at, id)
) COMMENT '告警人工处理历史，操作人信息保存快照';
