-- 告警前后帧各占一个位置，触发帧仍使用 alert_record 中已有的关键帧。
CREATE TABLE IF NOT EXISTS alert_timeline_frame (
    alert_id BIGINT NOT NULL,
    frame_offset TINYINT NOT NULL COMMENT '-1 前一帧，1 后一帧',
    captured_at DATETIME(3) NOT NULL,
    key_frame_path VARCHAR(500) NOT NULL,
    PRIMARY KEY (alert_id, frame_offset),
    CONSTRAINT fk_alert_timeline_alert FOREIGN KEY (alert_id) REFERENCES alert_record(id) ON DELETE CASCADE
) COMMENT '告警关键帧时间线';
