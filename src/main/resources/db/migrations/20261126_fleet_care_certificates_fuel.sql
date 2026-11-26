SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='vehicle_maintenance_plan' AND column_name='enabled'),
    'SELECT 1', 'ALTER TABLE vehicle_maintenance_plan ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='vehicle_repair' AND column_name='maintenance_plan_id'),
    'SELECT 1', 'ALTER TABLE vehicle_repair ADD COLUMN maintenance_plan_id BIGINT NULL'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='vehicle_repair' AND column_name='completed_on'),
    'SELECT 1', 'ALTER TABLE vehicle_repair ADD COLUMN completed_on DATE NULL'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='vehicle_repair' AND column_name='completed_odometer_km'),
    'SELECT 1', 'ALTER TABLE vehicle_repair ADD COLUMN completed_odometer_km DECIMAL(12,2) NULL'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='vehicle_repair' AND column_name='maintenance_cycle_applied'),
    'SELECT 1', 'ALTER TABLE vehicle_repair ADD COLUMN maintenance_cycle_applied INT NULL'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='fuel_record' AND column_name='fill_status'),
    'SELECT 1', 'ALTER TABLE fuel_record ADD COLUMN fill_status VARCHAR(8) NOT NULL DEFAULT ''UNKNOWN'''
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='fuel_record' AND column_name='version'),
    'SELECT 1', 'ALTER TABLE fuel_record ADD COLUMN version BIGINT NOT NULL DEFAULT 1'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='fuel_record' AND column_name='voided_at'),
    'SELECT 1', 'ALTER TABLE fuel_record ADD COLUMN voided_at DATETIME(3) NULL'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='fuel_record' AND column_name='active_marker'),
    'SELECT 1', 'ALTER TABLE fuel_record ADD COLUMN active_marker TINYINT GENERATED ALWAYS AS (IF(voided_at IS NULL,1,NULL)) STORED'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='operation_certificate' AND column_name='version'),
    'SELECT 1', 'ALTER TABLE operation_certificate ADD COLUMN version BIGINT NOT NULL DEFAULT 1'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='fuel_record' AND index_name='uk_vehicle_active_fuel_time'),
    'SELECT 1', 'ALTER TABLE fuel_record ADD UNIQUE KEY uk_vehicle_active_fuel_time (vehicle_id, fueled_at, active_marker)'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

SET @care_ddl = IF(
    NOT EXISTS(SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='fuel_record' AND index_name='uk_vehicle_fuel_time'),
    'SELECT 1', 'ALTER TABLE fuel_record DROP INDEX uk_vehicle_fuel_time'
);
PREPARE care_stmt FROM @care_ddl;
EXECUTE care_stmt;
DEALLOCATE PREPARE care_stmt;

CREATE TABLE IF NOT EXISTS vehicle_repair_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repair_id BIGINT NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NOT NULL,
    reason VARCHAR(300) NULL,
    operator_id BIGINT NOT NULL,
    operated_at DATETIME(3) NOT NULL,
    completed_on DATE NULL,
    completed_odometer_km DECIMAL(12,2) NULL,
    maintenance_cycle_applied INT NULL,
    KEY idx_repair_event (repair_id,id)
) COMMENT '维修工单状态与实绩历史';

CREATE TABLE IF NOT EXISTS fuel_record_revision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fuel_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    action VARCHAR(12) NOT NULL,
    operator_id BIGINT NOT NULL,
    reason VARCHAR(300) NOT NULL,
    before_json JSON NOT NULL,
    after_json JSON NOT NULL,
    operated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_fuel_revision (fuel_id,version)
) COMMENT '加油修正和作废记录';
