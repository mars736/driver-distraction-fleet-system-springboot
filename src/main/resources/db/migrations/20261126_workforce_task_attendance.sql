SET @workforce_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='transport_task' AND column_name='request_key'),
    'SELECT 1', 'ALTER TABLE transport_task ADD COLUMN request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL'
);
PREPARE workforce_stmt FROM @workforce_ddl;
EXECUTE workforce_stmt;
DEALLOCATE PREPARE workforce_stmt;

SET @workforce_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='transport_task' AND index_name='uk_task_actor_request'),
    'SELECT 1', 'ALTER TABLE transport_task ADD UNIQUE KEY uk_task_actor_request (created_by, request_key)'
);
PREPARE workforce_stmt FROM @workforce_ddl;
EXECUTE workforce_stmt;
DEALLOCATE PREPARE workforce_stmt;
