-- 申诉不修改识别原始数据。状态、事件和材料元数据同事务保存。
CREATE TABLE IF NOT EXISTS alert_appeal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type VARCHAR(16) NOT NULL COMMENT 'ALERT / RECOGNITION',
    target_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    fleet_id BIGINT NULL,
    vehicle_id BIGINT NULL,
    reason VARCHAR(1000) NOT NULL,
    target_status_before VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / WITHDRAWN / CONFIRMED / REVOKED',
    decision_reason VARCHAR(500) NULL,
    reviewer_id BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    KEY idx_appeal_target (target_type,target_id,id),
    KEY idx_appeal_driver (driver_id,status,create_time,id),
    KEY idx_appeal_status (status,create_time,id)
) COMMENT '司机申诉';

CREATE TABLE IF NOT EXISTS alert_appeal_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appeal_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    operator_id BIGINT NOT NULL,
    operator_username VARCHAR(64) NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    operator_role VARCHAR(32) NOT NULL,
    alert_review_id BIGINT NULL,
    material_id BIGINT NULL,
    operated_at DATETIME(3) NOT NULL,
    KEY idx_appeal_event (appeal_id,id)
) COMMENT '申诉不可覆盖的操作历史';

CREATE TABLE IF NOT EXISTS alert_appeal_material (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appeal_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    relative_path VARCHAR(255) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    uploaded_at DATETIME(3) NOT NULL,
    KEY idx_appeal_material (appeal_id,id)
) COMMENT '申诉材料，通过鉴权接口读取';
