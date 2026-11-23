SET @fleet_track_index_sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE vehicle_telemetry ADD INDEX idx_vehicle_telemetry_track (vehicle_id, sampled_at, driver_id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'vehicle_telemetry'
      AND index_name = 'idx_vehicle_telemetry_track'
);
PREPARE fleet_track_index_stmt FROM @fleet_track_index_sql;
EXECUTE fleet_track_index_stmt;
DEALLOCATE PREPARE fleet_track_index_stmt;
