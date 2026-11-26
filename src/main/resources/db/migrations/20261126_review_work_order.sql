CREATE TABLE IF NOT EXISTS work_order_legacy_revocation_note (
    work_order_id BIGINT PRIMARY KEY,
    work_done VARCHAR(500) NOT NULL,
    resolution VARCHAR(500) NOT NULL,
    recorded_by BIGINT NOT NULL,
    recorded_at DATETIME(3) NOT NULL
) COMMENT '历史撤销工单补记';
