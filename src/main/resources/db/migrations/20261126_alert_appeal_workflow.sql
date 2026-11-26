-- 历史工单和事发名称的现有值保持原样。

SET @workflow_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='violation_work_order' AND column_name='revocation_source'),
    'SELECT 1', 'ALTER TABLE violation_work_order ADD COLUMN revocation_source VARCHAR(16) NULL'
);
PREPARE workflow_stmt FROM @workflow_ddl;
EXECUTE workflow_stmt;
DEALLOCATE PREPARE workflow_stmt;

SET @workflow_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='violation_work_order' AND column_name='revocation_review_id'),
    'SELECT 1', 'ALTER TABLE violation_work_order ADD COLUMN revocation_review_id BIGINT NULL'
);
PREPARE workflow_stmt FROM @workflow_ddl;
EXECUTE workflow_stmt;
DEALLOCATE PREPARE workflow_stmt;

SET @workflow_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='violation_work_order' AND column_name='revoked_at'),
    'SELECT 1', 'ALTER TABLE violation_work_order ADD COLUMN revoked_at DATETIME(3) NULL'
);
PREPARE workflow_stmt FROM @workflow_ddl;
EXECUTE workflow_stmt;
DEALLOCATE PREPARE workflow_stmt;

SET @workflow_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='violation_work_order' AND column_name='wrapup_work_done'),
    'SELECT 1', 'ALTER TABLE violation_work_order ADD COLUMN wrapup_work_done VARCHAR(500) NULL'
);
PREPARE workflow_stmt FROM @workflow_ddl;
EXECUTE workflow_stmt;
DEALLOCATE PREPARE workflow_stmt;

SET @workflow_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='recognition_record' AND column_name='names_captured'),
    'SELECT 1', 'ALTER TABLE recognition_record ADD COLUMN names_captured TINYINT(1) NOT NULL DEFAULT 0'
);
PREPARE workflow_stmt FROM @workflow_ddl;
EXECUTE workflow_stmt;
DEALLOCATE PREPARE workflow_stmt;

SET @workflow_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='recognition_record' AND column_name='plate_no_snapshot'),
    'SELECT 1', 'ALTER TABLE recognition_record ADD COLUMN plate_no_snapshot VARCHAR(16) NULL'
);
PREPARE workflow_stmt FROM @workflow_ddl;
EXECUTE workflow_stmt;
DEALLOCATE PREPARE workflow_stmt;

SET @workflow_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='recognition_record' AND column_name='driver_name_snapshot'),
    'SELECT 1', 'ALTER TABLE recognition_record ADD COLUMN driver_name_snapshot VARCHAR(64) NULL'
);
PREPARE workflow_stmt FROM @workflow_ddl;
EXECUTE workflow_stmt;
DEALLOCATE PREPARE workflow_stmt;

SET @workflow_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='recognition_record' AND column_name='fleet_name_snapshot'),
    'SELECT 1', 'ALTER TABLE recognition_record ADD COLUMN fleet_name_snapshot VARCHAR(64) NULL'
);
PREPARE workflow_stmt FROM @workflow_ddl;
EXECUTE workflow_stmt;
DEALLOCATE PREPARE workflow_stmt;
