CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NULL,
    fleet_id BIGINT NULL,
    action VARCHAR(40) NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    target_id BIGINT NULL,
    outcome VARCHAR(12) NOT NULL,
    detail_code VARCHAR(40) NULL,
    request_id CHAR(36) NOT NULL,
    operated_at DATETIME(3) NOT NULL,
    KEY idx_operation_time (operated_at, id),
    KEY idx_operation_fleet_time (fleet_id, operated_at, id),
    KEY idx_operation_actor_time (actor_id, operated_at, id)
) COMMENT '业务操作审计';
