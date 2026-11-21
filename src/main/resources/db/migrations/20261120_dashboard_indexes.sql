-- 工作台按采集时间汇总；已有车辆和司机索引以归属字段开头。
-- MySQL 8 不支持 ADD INDEX IF NOT EXISTS，先检查索引以兼容已执行新 schema 的库。
SET @dashboard_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'recognition_record'
      AND index_name = 'idx_recognition_dashboard_time'
), 'DO 0', 'ALTER TABLE recognition_record ADD INDEX idx_recognition_dashboard_time (captured_at)');
PREPARE dashboard_stmt FROM @dashboard_ddl;
EXECUTE dashboard_stmt;
DEALLOCATE PREPARE dashboard_stmt;

SET @dashboard_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'alert_record'
      AND index_name = 'idx_alert_dashboard_time'
), 'DO 0', 'ALTER TABLE alert_record ADD INDEX idx_alert_dashboard_time (captured_at)');
PREPARE dashboard_stmt FROM @dashboard_ddl;
EXECUTE dashboard_stmt;
DEALLOCATE PREPARE dashboard_stmt;
SET @dashboard_ddl = NULL;
