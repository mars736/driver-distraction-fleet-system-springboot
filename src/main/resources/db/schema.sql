CREATE TABLE IF NOT EXISTS camera (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NULL,
    driver_id BIGINT NULL,
    stream_url VARCHAR(500) NOT NULL,
    sample_interval_seconds DOUBLE NOT NULL DEFAULT 2.0,
    config_version BIGINT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 车队：整个系统只有一个公司，车队就是唯一的组织层级
CREATE TABLE IF NOT EXISTS fleet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fleet_no VARCHAR(32) NOT NULL COMMENT '车队编号',
    name VARCHAR(64) NOT NULL COMMENT '车队名称',
    manager VARCHAR(32) NULL COMMENT '负责人',
    phone VARCHAR(20) NULL COMMENT '联系电话',
    established_date DATE NULL COMMENT '成立时间',
    remark VARCHAR(255) NULL COMMENT '备注',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1 启用，0 停用',
    stopped_at BIGINT NULL COMMENT '最近一次停运起点，Unix 毫秒',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fleet_no (fleet_no)
) COMMENT '车队';

-- 统一账号：司机和管理员共用一张表，用 role 区分
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL COMMENT '登录账号，司机=工号，管理员=admin',
    password VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    name VARCHAR(64) NOT NULL COMMENT '姓名',
    phone VARCHAR(20) NULL COMMENT '手机号',
    fleet_id BIGINT NULL COMMENT '所属车队；平台管理员为空',
    employee_no VARCHAR(64) NULL COMMENT '工号，全局唯一',
    role VARCHAR(32) NOT NULL DEFAULT 'DRIVER' COMMENT 'PLATFORM_ADMIN / DRIVER',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1 启用，0 禁用',
    token_version INT NOT NULL DEFAULT 0 COMMENT '禁用/改密时 +1，旧 token 立即失效',
    vehicle_id BIGINT NULL COMMENT '绑定车辆',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_employee_no (employee_no),
    KEY idx_fleet_id (fleet_id)
) COMMENT '统一账号';

-- 车辆：属于某个车队
CREATE TABLE IF NOT EXISTS vehicle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fleet_id BIGINT NOT NULL COMMENT '所属车队',
    plate_no VARCHAR(16) NOT NULL COMMENT '车牌号',
    vin VARCHAR(32) NULL COMMENT 'VIN 码',
    vehicle_type VARCHAR(32) NULL COMMENT '车型',
    rated_tonnage DECIMAL(6, 2) NULL COMMENT '核定吨位',
    purchase_date DATE NULL COMMENT '购置日期',
    monitor_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1 启用监控，0 停用',
    remark VARCHAR(255) NULL COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plate_no (plate_no),
    KEY idx_fleet_id (fleet_id)
) COMMENT '车辆';

-- 识别元数据；source_frame_id 仅供排查，重试去重使用 source_key。
CREATE TABLE IF NOT EXISTS recognition_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_key VARCHAR(36) NOT NULL COMMENT '本次采集结果的 UUID，重试时保持不变',
    source_frame_id BIGINT NOT NULL COMMENT '进程内帧号，非数据库主键',
    camera_id BIGINT NULL,
    vehicle_id BIGINT NULL,
    driver_id BIGINT NULL,
    fleet_id BIGINT NULL,
    captured_at DATETIME(3) NOT NULL,
    pred INT NOT NULL COMMENT '模型原始类别索引，OOD 时不可信',
    behavior_code VARCHAR(8) NULL COMMENT '有效类别 c0-c9，OOD/非法预测为空',
    max_p DOUBLE NULL,
    energy DOUBLE NULL,
    knn_dist DOUBLE NULL,
    ood TINYINT(1) NOT NULL,
    participated TINYINT(1) NOT NULL COMMENT '是否参与行为判定',
    decision_reason VARCHAR(32) NOT NULL,
    speed_kmh DOUBLE NULL,
    speed_status VARCHAR(16) NOT NULL COMMENT 'KNOWN / UNKNOWN',
    key_frame_path VARCHAR(255) NULL COMMENT '仅违规触发帧保存 JPEG，相对上传目录',
    names_captured TINYINT(1) NOT NULL DEFAULT 0,
    plate_no_snapshot VARCHAR(16) NULL,
    driver_name_snapshot VARCHAR(64) NULL,
    fleet_name_snapshot VARCHAR(64) NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_recognition_source (source_key),
    KEY idx_recognition_vehicle_time (vehicle_id, captured_at),
    KEY idx_recognition_driver_time (driver_id, captured_at),
    KEY idx_recognition_dashboard_time (captured_at),
    KEY idx_recognition_key_frame_path (key_frame_path)
) COMMENT '识别记录';

CREATE TABLE IF NOT EXISTS alert_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recognition_id BIGINT NOT NULL,
    camera_id BIGINT NULL,
    vehicle_id BIGINT NOT NULL,
    driver_id BIGINT NULL,
    fleet_id BIGINT NULL,
    captured_at DATETIME(3) NOT NULL,
    behavior_code VARCHAR(8) NULL,
    max_p DOUBLE NULL,
    record_type VARCHAR(32) NOT NULL COMMENT 'SMS_ALERT / BACKLOG / SPEED_UNKNOWN；速度异常不作为司机违规',
    event_count INT NOT NULL DEFAULT 0,
    sms_status VARCHAR(40) NOT NULL COMMENT 'NOT_REQUIRED、处理中、模拟结果、真实发送及回执状态',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CONFIRMED / REVOKED',
    archive_revision BIGINT NOT NULL DEFAULT 0,
    key_frame_path VARCHAR(255) NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_alert_recognition_type (recognition_id, record_type),
    KEY idx_alert_vehicle_time (vehicle_id, captured_at),
    KEY idx_alert_driver_time (driver_id, captured_at),
    KEY idx_alert_sms (sms_status),
    KEY idx_alert_dashboard_time (captured_at)
) COMMENT '告警与速度未知异常';

CREATE TABLE IF NOT EXISTS alert_archive_batch (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    partition_date DATE NOT NULL,
    next_attempt_at DATETIME(3) NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    synced_at DATETIME(3) NULL,
    last_error VARCHAR(255) NULL,
    KEY idx_alert_archive_due (synced_at, next_attempt_at, id)
) COMMENT '待写入 HDFS 的不可变归档批次';

CREATE TABLE IF NOT EXISTS alert_archive_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    recognition_id BIGINT NOT NULL,
    driver_id BIGINT NULL,
    vehicle_id BIGINT NOT NULL,
    fleet_id BIGINT NULL,
    camera_id BIGINT NULL,
    captured_at DATETIME(3) NOT NULL,
    record_type VARCHAR(32) NOT NULL,
    behavior_code VARCHAR(8) NULL,
    status VARCHAR(16) NOT NULL,
    source VARCHAR(20) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    UNIQUE KEY uk_alert_archive_version (alert_id, revision),
    KEY idx_alert_archive_events_batch (batch_id, id)
) COMMENT '用于 HDFS 归档的告警状态事件';

CREATE TABLE IF NOT EXISTS alert_archive_backfill (
    id TINYINT PRIMARY KEY,
    through_id BIGINT NOT NULL,
    last_id BIGINT NOT NULL DEFAULT 0
) COMMENT '历史告警归档回填进度';

CREATE TABLE IF NOT EXISTS alert_vehicle_state (
    vehicle_id BIGINT PRIMARY KEY,
    state_json LONGTEXT NOT NULL COMMENT '车辆判定状态，和识别记录及告警同事务更新',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '告警累计与去重状态，支持应用重启恢复';

-- 人工处理只保存确认/撤销与历史，不回流模型、不修改识别元数据或短信投递状态。
CREATE TABLE IF NOT EXISTS alert_review_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    from_status VARCHAR(16) NOT NULL,
    to_status VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    operator_id BIGINT NOT NULL,
    operator_username VARCHAR(64) NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    operated_at DATETIME(3) NOT NULL COMMENT '北京时间操作时间',
    KEY idx_alert_review_time (alert_id, operated_at, id)
) COMMENT '告警人工处理历史，操作人信息保存快照';

-- 申诉不修改识别原始数据。状态、事件和材料元数据同事务保存。
CREATE TABLE IF NOT EXISTS alert_appeal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type VARCHAR(16) NOT NULL COMMENT 'ALERT / RECOGNITION',
    target_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    fleet_id BIGINT NULL,
    vehicle_id BIGINT NULL,
    reason VARCHAR(1000) NOT NULL,
    target_status_before VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / WITHDRAWN / CONFIRMED / REVOKED',
    decision_reason VARCHAR(500) NULL,
    reviewer_id BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    KEY idx_appeal_target (target_type,target_id,id),
    KEY idx_appeal_driver (driver_id,status,create_time,id),
    KEY idx_appeal_status (status,create_time,id)
) COMMENT '司机申诉';

CREATE TABLE IF NOT EXISTS alert_appeal_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appeal_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    operator_id BIGINT NOT NULL,
    operator_username VARCHAR(64) NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    operator_role VARCHAR(32) NOT NULL,
    alert_review_id BIGINT NULL,
    material_id BIGINT NULL,
    operated_at DATETIME(3) NOT NULL,
    KEY idx_appeal_event (appeal_id,id)
) COMMENT '申诉不可覆盖的操作历史';

CREATE TABLE IF NOT EXISTS alert_appeal_material (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appeal_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    relative_path VARCHAR(255) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    uploaded_at DATETIME(3) NOT NULL,
    KEY idx_appeal_material (appeal_id,id)
) COMMENT '申诉材料，通过鉴权接口读取';

CREATE TABLE IF NOT EXISTS alert_timeline_frame (
    alert_id BIGINT NOT NULL,
    frame_offset TINYINT NOT NULL COMMENT '-1 前一帧，1 后一帧',
    captured_at DATETIME(3) NOT NULL,
    key_frame_path VARCHAR(500) NOT NULL,
    PRIMARY KEY (alert_id, frame_offset),
    CONSTRAINT fk_alert_timeline_alert FOREIGN KEY (alert_id) REFERENCES alert_record(id) ON DELETE CASCADE
) COMMENT '告警关键帧时间线';

CREATE TABLE IF NOT EXISTS vehicle_telemetry (
    vehicle_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    sampled_at BIGINT NOT NULL COMMENT '手机 GPS 采样时间，Unix 毫秒',
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    speed_kmh DOUBLE NULL COMMENT '设备未取得有效速度时为空',
    received_at BIGINT NULL COMMENT '后端接收时间，Unix 毫秒',
    coordinate_system VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN',
    ingest_id BIGINT NOT NULL AUTO_INCREMENT,
    UNIQUE KEY uk_vehicle_telemetry_ingest (ingest_id),
    KEY idx_vehicle_telemetry_watermark (vehicle_id, ingest_id),
    PRIMARY KEY (vehicle_id, driver_id, sampled_at),
    KEY idx_vehicle_telemetry_track (vehicle_id, sampled_at, driver_id)
) COMMENT '司机 App 车辆定位与速度样本';

CREATE TABLE IF NOT EXISTS vehicle_task_offline_notice (
    task_id BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    last_sampled_at BIGINT NOT NULL,
    detected_at BIGINT NOT NULL,
    PRIMARY KEY (task_id, vehicle_id, driver_id, last_sampled_at)
) COMMENT '任务内车辆定位离线提示去重';

CREATE TABLE IF NOT EXISTS vehicle_stopped_motion_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL,
    fleet_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    stopped_at BIGINT NOT NULL,
    first_sampled_at BIGINT NOT NULL,
    second_sampled_at BIGINT NOT NULL,
    detected_at BIGINT NOT NULL,
    UNIQUE KEY uk_stopped_motion_samples (vehicle_id, driver_id, stopped_at, first_sampled_at, second_sampled_at)
) COMMENT '停运车队车辆行驶核查证据';

CREATE TABLE IF NOT EXISTS vehicle_stopped_motion_state (
    vehicle_id BIGINT PRIMARY KEY,
    fleet_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    stopped_at BIGINT NOT NULL,
    last_sampled_at BIGINT NOT NULL,
    above_threshold BOOLEAN NOT NULL,
    check_id BIGINT NULL
) COMMENT '停运车辆连续车速状态';

CREATE TABLE IF NOT EXISTS vehicle_offline_notice (
    vehicle_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    last_sampled_at BIGINT NOT NULL,
    detected_at BIGINT NOT NULL,
    PRIMARY KEY (vehicle_id, driver_id, last_sampled_at)
) COMMENT '车辆定位离线通知去重记录';

CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NULL,
    fleet_id BIGINT NULL,
    action VARCHAR(40) NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    target_id BIGINT NULL,
    outcome VARCHAR(12) NOT NULL,
    detail_code VARCHAR(40) NULL,
    request_id CHAR(36) NOT NULL,
    operated_at DATETIME(3) NOT NULL,
    KEY idx_operation_time (operated_at, id),
    KEY idx_operation_fleet_time (fleet_id, operated_at, id),
    KEY idx_operation_actor_time (actor_id, operated_at, id)
) COMMENT '业务操作审计';

CREATE TABLE IF NOT EXISTS sms_delivery (
    alert_id BIGINT NOT NULL PRIMARY KEY,
    out_id VARCHAR(64) NOT NULL,
    recipient VARCHAR(16) NULL,
    attempted_at DATETIME(3) NULL,
    next_check_at DATETIME(3) NULL,
    provider_message_id VARCHAR(128) NULL,
    provider_code VARCHAR(80) NULL,
    UNIQUE KEY uk_sms_delivery_out_id (out_id),
    KEY idx_sms_delivery_next_check (next_check_at, alert_id),
    CONSTRAINT fk_sms_delivery_alert FOREIGN KEY (alert_id) REFERENCES alert_record(id) ON DELETE CASCADE
) COMMENT '真实短信派发与回执';

CREATE TABLE IF NOT EXISTS recognition_file_cleanup (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    path VARCHAR(255) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_recognition_cleanup_path (path),
    KEY idx_recognition_cleanup_due (next_run_at, id)
) COMMENT '识别记录删除后的关键帧清理任务';

CREATE TABLE IF NOT EXISTS report_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    report_type VARCHAR(24) NOT NULL,
    format VARCHAR(8) NOT NULL,
    filters_json JSON NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    expires_at DATETIME(3) NULL,
    data_as_of DATETIME(3) NULL,
    run_token CHAR(36) NULL,
    attempts INT NOT NULL DEFAULT 0,
    row_count INT NULL,
    error_message VARCHAR(200) NULL,
    KEY idx_report_owner (owner_id, id),
    KEY idx_report_status (status, id),
    KEY idx_report_expiry (status, expires_at),
    UNIQUE KEY uk_report_run_token (run_token),
    UNIQUE KEY uk_report_owner_request (owner_id, request_key)
) COMMENT '异步报表与导出历史';

CREATE TABLE IF NOT EXISTS driver_shift (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    driver_id BIGINT NOT NULL,
    fleet_id BIGINT NOT NULL,
    shift_date DATE NOT NULL,
    shift_type VARCHAR(8) NOT NULL,
    starts_at DATETIME(3) NULL,
    ends_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    UNIQUE KEY uk_driver_shift_date (driver_id, shift_date)
) COMMENT '司机排班';

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

CREATE TABLE IF NOT EXISTS transport_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fleet_id BIGINT NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    description VARCHAR(500) NOT NULL,
    origin_address VARCHAR(255) NOT NULL,
    destination_address VARCHAR(255) NOT NULL,
    starts_at DATETIME(3) NOT NULL,
    ends_at DATETIME(3) NOT NULL,
    requirement VARCHAR(8) NOT NULL DEFAULT 'NORMAL',
    version BIGINT NOT NULL DEFAULT 1,
    current_task_id BIGINT NULL,
    canceled_at DATETIME(3) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_transport_order_no (order_no),
    KEY idx_transport_order_fleet (fleet_id, id),
    CONSTRAINT fk_transport_order_fleet FOREIGN KEY (fleet_id) REFERENCES fleet(id) ON DELETE RESTRICT
) COMMENT '待派运输订单';

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
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    transport_order_id BIGINT NULL,
    origin_address VARCHAR(255) NULL,
    destination_address VARCHAR(255) NULL,
    requirement VARCHAR(8) NOT NULL DEFAULT 'NORMAL',
    order_dispatch_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dispatch_order_version BIGINT NULL,
    UNIQUE KEY uk_task_actor_request (created_by, request_key),
    UNIQUE KEY uk_transport_order_dispatch (transport_order_id, order_dispatch_key),
    CONSTRAINT fk_transport_task_order FOREIGN KEY (transport_order_id) REFERENCES transport_order(id) ON DELETE RESTRICT,
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
    enabled TINYINT(1) NOT NULL DEFAULT 1,
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
    maintenance_plan_id BIGINT NULL,
    completed_on DATE NULL,
    completed_odometer_km DECIMAL(12,2) NULL,
    maintenance_cycle_applied INT NULL,
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
    fill_status VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN',
    version BIGINT NOT NULL DEFAULT 1,
    voided_at DATETIME(3) NULL,
    active_marker TINYINT GENERATED ALWAYS AS (IF(voided_at IS NULL,1,NULL)) STORED,
    UNIQUE KEY uk_vehicle_active_fuel_time (vehicle_id, fueled_at, active_marker)
) COMMENT '加油记录与里程表读数';

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

CREATE TABLE IF NOT EXISTS operation_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subject_type VARCHAR(8) NOT NULL,
    subject_id BIGINT NOT NULL,
    certificate_type VARCHAR(24) NOT NULL,
    expires_on DATE NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
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

CREATE TABLE IF NOT EXISTS violation_work_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    risk_level VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    latitude DOUBLE NULL,
    longitude DOUBLE NULL,
    position_sampled_at BIGINT NULL,
    assignee_id BIGINT NULL,
    assigned_at DATETIME(3) NULL,
    started_at DATETIME(3) NULL,
    closed_at DATETIME(3) NULL,
    closed_by BIGINT NULL,
    outcome VARCHAR(20) NULL,
    resolution VARCHAR(500) NULL,
    revocation_source VARCHAR(16) NULL,
    revocation_review_id BIGINT NULL,
    revoked_at DATETIME(3) NULL,
    wrapup_work_done VARCHAR(500) NULL,
    recommendation_action VARCHAR(20) NULL,
    recommendation_note VARCHAR(500) NULL,
    recommendation_by BIGINT NULL,
    recommendation_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_work_order_alert (alert_id),
    KEY idx_work_order_queue (status, risk_level, id),
    KEY idx_work_order_assignment (assignee_id, status),
    CONSTRAINT fk_work_order_alert FOREIGN KEY (alert_id) REFERENCES alert_record(id) ON DELETE CASCADE
) COMMENT '违规告警处置工单';

CREATE TABLE IF NOT EXISTS work_order_legacy_revocation_note (
    work_order_id BIGINT PRIMARY KEY,
    work_done VARCHAR(500) NOT NULL,
    resolution VARCHAR(500) NOT NULL,
    recorded_by BIGINT NOT NULL,
    recorded_at DATETIME(3) NOT NULL
) COMMENT '历史撤销工单补记';

CREATE TABLE IF NOT EXISTS dispatch_operator_location (
    user_id BIGINT PRIMARY KEY,
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    reported_at DATETIME(3) NOT NULL
) COMMENT '平台管理员用于派发的最近位置';

-- ==================== 车辆引用完整性外键（服务层校验之外的数据库兜底） ====================
-- 注意：若现有数据存在悬空引用，执行下面 ALTER 前需先清理，否则会加外键失败。
ALTER TABLE vehicle
    ADD CONSTRAINT fk_vehicle_fleet FOREIGN KEY (fleet_id) REFERENCES fleet(id) ON DELETE RESTRICT;

ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id) ON DELETE RESTRICT;

ALTER TABLE camera
    ADD CONSTRAINT fk_camera_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id) ON DELETE RESTRICT;

CREATE TABLE IF NOT EXISTS alert_video_evidence (
    alert_id BIGINT PRIMARY KEY,
    camera_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    stream_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    captured_at_ms BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    actual_start_ms BIGINT NULL,
    actual_end_ms BIGINT NULL,
    byte_size INT NULL,
    next_attempt_ms BIGINT NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(80) NULL,
    KEY idx_video_evidence_due (status, next_attempt_ms, alert_id)
) COMMENT '告警视频证据及独立归档进度';
