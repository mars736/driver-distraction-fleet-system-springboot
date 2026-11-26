SET @camera_config_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'camera' AND column_name = 'config_version'),
    'SELECT 1', 'ALTER TABLE camera ADD COLUMN config_version BIGINT NOT NULL DEFAULT 0'
);
PREPARE camera_config_stmt FROM @camera_config_ddl;
EXECUTE camera_config_stmt;
DEALLOCATE PREPARE camera_config_stmt;

UPDATE operation_log
SET actor_id = NULL
WHERE action = 'AUTH_LOGIN' AND outcome = 'FAILURE' AND actor_id IS NOT NULL;
