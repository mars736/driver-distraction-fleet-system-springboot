CREATE TABLE IF NOT EXISTS driver_shift (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    driver_id BIGINT NOT NULL,
    fleet_id BIGINT NOT NULL,
    shift_date DATE NOT NULL,
    shift_type VARCHAR(8) NOT NULL,
    starts_at DATETIME(3) NULL,
    ends_at DATETIME(3) NULL,
    UNIQUE KEY uk_driver_shift_date (driver_id, shift_date)
) COMMENT '司机排班';

CREATE TABLE IF NOT EXISTS driver_attendance (
    shift_id BIGINT PRIMARY KEY,
    clock_in_at DATETIME(3) NOT NULL,
    clock_in_lat DOUBLE NOT NULL,
    clock_in_lon DOUBLE NOT NULL,
    clock_out_at DATETIME(3) NULL,
    clock_out_lat DOUBLE NULL,
    clock_out_lon DOUBLE NULL
) COMMENT '排班签到签退';

CREATE TABLE IF NOT EXISTS driver_leave (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    driver_id BIGINT NOT NULL,
    fleet_id BIGINT NOT NULL,
    leave_type VARCHAR(16) NOT NULL,
    starts_at DATETIME(3) NOT NULL,
    ends_at DATETIME(3) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    status VARCHAR(12) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    decided_by BIGINT NULL,
    decided_at DATETIME(3) NULL,
    decision_note VARCHAR(300) NULL,
    KEY idx_driver_leave_interval (driver_id, status, starts_at, ends_at)
) COMMENT '司机请假和审批';

CREATE TABLE IF NOT EXISTS transport_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    driver_id BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    fleet_id BIGINT NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    description VARCHAR(500) NOT NULL,
    starts_at DATETIME(3) NOT NULL,
    ends_at DATETIME(3) NOT NULL,
    status VARCHAR(12) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    departed_at DATETIME(3) NULL,
    depart_lat DOUBLE NULL,
    depart_lon DOUBLE NULL,
    ended_at DATETIME(3) NULL,
    end_lat DOUBLE NULL,
    end_lon DOUBLE NULL,
    KEY idx_transport_driver_interval (driver_id, status, starts_at, ends_at),
    KEY idx_transport_vehicle_interval (vehicle_id, status, starts_at, ends_at)
) COMMENT '运输任务和出发完成报到';

CREATE TABLE IF NOT EXISTS vehicle_odometer (
    vehicle_id BIGINT PRIMARY KEY,
    odometer_km DECIMAL(12,2) NOT NULL,
    measured_at DATETIME(3) NOT NULL
) COMMENT '车辆人工核对里程表读数';

CREATE TABLE IF NOT EXISTS vehicle_maintenance_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    interval_days INT NULL,
    interval_km DECIMAL(12,2) NULL,
    next_date DATE NULL,
    next_odometer_km DECIMAL(12,2) NULL,
    cycle INT NOT NULL DEFAULT 1,
    last_service_date DATE NULL,
    last_service_odometer_km DECIMAL(12,2) NULL,
    KEY idx_maintenance_vehicle (vehicle_id, id)
) COMMENT '车辆周期保养计划';

CREATE TABLE IF NOT EXISTS vehicle_repair (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL,
    description VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL,
    assignee VARCHAR(80) NULL,
    repair_shop VARCHAR(100) NULL,
    cost DECIMAL(12,2) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_repair_vehicle (vehicle_id, id)
) COMMENT '车辆维修工单与费用';

CREATE TABLE IF NOT EXISTS fuel_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL,
    fueled_at DATETIME(3) NOT NULL,
    liters DECIMAL(9,2) NOT NULL,
    unit_price DECIMAL(8,3) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    odometer_km DECIMAL(12,2) NOT NULL,
    UNIQUE KEY uk_vehicle_fuel_time (vehicle_id, fueled_at)
) COMMENT '加油记录与里程表读数';

CREATE TABLE IF NOT EXISTS operation_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subject_type VARCHAR(8) NOT NULL,
    subject_id BIGINT NOT NULL,
    certificate_type VARCHAR(24) NOT NULL,
    expires_on DATE NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_operations_certificate (subject_type, subject_id, certificate_type),
    KEY idx_operations_certificate_due (expires_on, id)
) COMMENT '车辆和司机证照';

CREATE TABLE IF NOT EXISTS operation_reminder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_type VARCHAR(16) NOT NULL,
    source_id BIGINT NOT NULL,
    cycle_key VARCHAR(32) NOT NULL,
    stage VARCHAR(12) NOT NULL,
    fleet_id BIGINT NOT NULL,
    subject_type VARCHAR(8) NOT NULL,
    subject_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    due_date DATE NULL,
    due_odometer_km DECIMAL(12,2) NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_operation_reminder (source_type, source_id, cycle_key, stage),
    KEY idx_operations_reminder_fleet (fleet_id, id)
) COMMENT '证照与保养到期提醒历史';
