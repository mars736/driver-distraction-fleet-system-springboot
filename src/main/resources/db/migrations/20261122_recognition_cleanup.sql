CREATE TABLE IF NOT EXISTS recognition_file_cleanup (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    path VARCHAR(255) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_recognition_cleanup_path (path),
    KEY idx_recognition_cleanup_due (next_run_at, id)
) COMMENT '识别记录删除后的关键帧清理任务';

SET @fleet_cleanup_index_sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE recognition_record ADD INDEX idx_recognition_key_frame_path (key_frame_path)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'recognition_record'
      AND index_name = 'idx_recognition_key_frame_path'
);
PREPARE fleet_cleanup_index_stmt FROM @fleet_cleanup_index_sql;
EXECUTE fleet_cleanup_index_stmt;
DEALLOCATE PREPARE fleet_cleanup_index_stmt;
