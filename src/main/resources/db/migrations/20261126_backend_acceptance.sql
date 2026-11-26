SET @acceptance_ddl=IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='driver_shift' AND column_name='version'),
    'SELECT 1', 'ALTER TABLE driver_shift ADD COLUMN version BIGINT NOT NULL DEFAULT 1'
);
PREPARE acceptance_stmt FROM @acceptance_ddl;
EXECUTE acceptance_stmt;
DEALLOCATE PREPARE acceptance_stmt;

CREATE TABLE IF NOT EXISTS driver_shift_revision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shift_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    changed_at DATETIME(3) NOT NULL,
    before_type VARCHAR(8) NULL,
    before_starts_at DATETIME(3) NULL,
    before_ends_at DATETIME(3) NULL,
    after_type VARCHAR(8) NOT NULL,
    after_starts_at DATETIME(3) NULL,
    after_ends_at DATETIME(3) NULL,
    UNIQUE KEY uk_shift_revision (shift_id,version)
) COMMENT '管理员排班变更历史';

SET @acceptance_ddl=IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='vehicle_telemetry' AND column_name='coordinate_system'),
    'SELECT 1', 'ALTER TABLE vehicle_telemetry ADD COLUMN coordinate_system VARCHAR(8) NOT NULL DEFAULT ''UNKNOWN'''
);
PREPARE acceptance_stmt FROM @acceptance_ddl;
EXECUTE acceptance_stmt;
DEALLOCATE PREPARE acceptance_stmt;

SET @acceptance_ddl=IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='vehicle_telemetry' AND column_name='ingest_id'),
    'SELECT 1', 'ALTER TABLE vehicle_telemetry ADD COLUMN ingest_id BIGINT NOT NULL AUTO_INCREMENT, ADD UNIQUE KEY uk_vehicle_telemetry_ingest (ingest_id), ADD KEY idx_vehicle_telemetry_watermark (vehicle_id, ingest_id)'
);
PREPARE acceptance_stmt FROM @acceptance_ddl;
EXECUTE acceptance_stmt;
DEALLOCATE PREPARE acceptance_stmt;

-- 以下外键拒绝孤儿记录。先按 README 核对并修复原归属，再重新执行迁移。

SET @acceptance_ddl=IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints
            WHERE table_schema=DATABASE() AND table_name='vehicle' AND constraint_name='fk_vehicle_fleet'),
    'SELECT 1', 'ALTER TABLE vehicle ADD CONSTRAINT fk_vehicle_fleet FOREIGN KEY (fleet_id) REFERENCES fleet(id) ON DELETE RESTRICT'
);
PREPARE acceptance_stmt FROM @acceptance_ddl;
EXECUTE acceptance_stmt;
DEALLOCATE PREPARE acceptance_stmt;

SET @acceptance_ddl=IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints
            WHERE table_schema=DATABASE() AND table_name='sys_user' AND constraint_name='fk_sys_user_vehicle'),
    'SELECT 1', 'ALTER TABLE sys_user ADD CONSTRAINT fk_sys_user_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id) ON DELETE RESTRICT'
);
PREPARE acceptance_stmt FROM @acceptance_ddl;
EXECUTE acceptance_stmt;
DEALLOCATE PREPARE acceptance_stmt;

SET @acceptance_ddl=IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints
            WHERE table_schema=DATABASE() AND table_name='camera' AND constraint_name='fk_camera_vehicle'),
    'SELECT 1', 'ALTER TABLE camera ADD CONSTRAINT fk_camera_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id) ON DELETE RESTRICT'
);
PREPARE acceptance_stmt FROM @acceptance_ddl;
EXECUTE acceptance_stmt;
DEALLOCATE PREPARE acceptance_stmt;
