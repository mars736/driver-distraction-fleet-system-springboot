CREATE TABLE IF NOT EXISTS violation_work_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    risk_level VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    latitude DOUBLE NULL,
    longitude DOUBLE NULL,
    position_sampled_at BIGINT NULL,
    assignee_id BIGINT NULL,
    assigned_at DATETIME(3) NULL,
    started_at DATETIME(3) NULL,
    closed_at DATETIME(3) NULL,
    closed_by BIGINT NULL,
    outcome VARCHAR(20) NULL,
    resolution VARCHAR(500) NULL,
    recommendation_action VARCHAR(20) NULL,
    recommendation_note VARCHAR(500) NULL,
    recommendation_by BIGINT NULL,
    recommendation_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_work_order_alert (alert_id),
    KEY idx_work_order_queue (status, risk_level, id),
    KEY idx_work_order_assignment (assignee_id, status),
    CONSTRAINT fk_work_order_alert FOREIGN KEY (alert_id) REFERENCES alert_record(id) ON DELETE CASCADE
) COMMENT '违规告警处置工单';

CREATE TABLE IF NOT EXISTS dispatch_operator_location (
    user_id BIGINT PRIMARY KEY,
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    reported_at DATETIME(3) NOT NULL
) COMMENT '平台管理员用于派发的最近位置';

INSERT INTO violation_work_order (alert_id,risk_level,latitude,longitude,position_sampled_at,created_at)
SELECT a.id,
       CASE WHEN a.behavior_code IN ('c1','c2','c3','c4') THEN 'HIGH' ELSE 'GENERAL' END,
       t.latitude,t.longitude,t.sampled_at,UTC_TIMESTAMP(3) + INTERVAL 8 HOUR
FROM alert_record a
LEFT JOIN vehicle_telemetry t ON t.vehicle_id=a.vehicle_id AND t.driver_id=a.driver_id
 AND t.sampled_at=(
    SELECT MAX(p.sampled_at) FROM vehicle_telemetry p
    WHERE p.vehicle_id=a.vehicle_id AND p.driver_id=a.driver_id
      AND p.sampled_at BETWEEN (TIMESTAMPDIFF(MICROSECOND,'1970-01-01 08:00:00',a.captured_at) DIV 1000)-15000
                           AND TIMESTAMPDIFF(MICROSECOND,'1970-01-01 08:00:00',a.captured_at) DIV 1000
 )
WHERE a.record_type IN ('SMS_ALERT','BACKLOG') AND a.status IN ('OPEN','CONFIRMED')
 AND NOT EXISTS(SELECT 1 FROM violation_work_order w WHERE w.alert_id=a.id)
ON DUPLICATE KEY UPDATE alert_id=violation_work_order.alert_id;
