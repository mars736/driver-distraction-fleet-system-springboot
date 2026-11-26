SET @report_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='report_job' AND column_name='request_key'),
    'SELECT 1', 'ALTER TABLE report_job ADD COLUMN request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL'
);
PREPARE report_stmt FROM @report_ddl;
EXECUTE report_stmt;
DEALLOCATE PREPARE report_stmt;

SET @report_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='report_job' AND column_name='data_as_of'),
    'SELECT 1', 'ALTER TABLE report_job ADD COLUMN data_as_of DATETIME(3) NULL'
);
PREPARE report_stmt FROM @report_ddl;
EXECUTE report_stmt;
DEALLOCATE PREPARE report_stmt;

SET @report_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='report_job' AND index_name='uk_report_owner_request'),
    'SELECT 1', 'ALTER TABLE report_job ADD UNIQUE KEY uk_report_owner_request (owner_id, request_key)'
);
PREPARE report_stmt FROM @report_ddl;
EXECUTE report_stmt;
DEALLOCATE PREPARE report_stmt;
