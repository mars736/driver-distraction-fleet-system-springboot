ALTER TABLE driver_shift ADD COLUMN version BIGINT NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS driver_shift_change (
    shift_id BIGINT NOT NULL,
    fleet_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    reason VARCHAR(300) NULL,
    changed_at DATETIME(3) NOT NULL,
    previous_version BIGINT NULL,
    previous_type VARCHAR(8) NULL,
    previous_starts_at DATETIME(3) NULL,
    previous_ends_at DATETIME(3) NULL,
    new_version BIGINT NOT NULL,
    new_type VARCHAR(8) NOT NULL,
    new_starts_at DATETIME(3) NULL,
    new_ends_at DATETIME(3) NULL,
    PRIMARY KEY (shift_id, new_version)
) COMMENT '司机排班变更历史';
