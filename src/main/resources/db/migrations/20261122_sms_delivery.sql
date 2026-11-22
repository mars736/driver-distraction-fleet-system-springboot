CREATE TABLE IF NOT EXISTS sms_delivery (
    alert_id BIGINT NOT NULL PRIMARY KEY,
    out_id VARCHAR(64) NOT NULL,
    recipient VARCHAR(16) NULL,
    attempted_at DATETIME(3) NULL,
    next_check_at DATETIME(3) NULL,
    provider_message_id VARCHAR(128) NULL,
    provider_code VARCHAR(80) NULL,
    UNIQUE KEY uk_sms_delivery_out_id (out_id),
    KEY idx_sms_delivery_next_check (next_check_at, alert_id),
    CONSTRAINT fk_sms_delivery_alert FOREIGN KEY (alert_id) REFERENCES alert_record(id) ON DELETE CASCADE
) COMMENT '真实短信派发与回执';
