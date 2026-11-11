CREATE TABLE IF NOT EXISTS camera (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NULL,
    driver_id BIGINT NULL,
    stream_url VARCHAR(500) NOT NULL,
    sample_interval_seconds DOUBLE NOT NULL DEFAULT 2.0,
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
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_recognition_source (source_key),
    KEY idx_recognition_vehicle_time (vehicle_id, captured_at),
    KEY idx_recognition_driver_time (driver_id, captured_at)
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
    sms_status VARCHAR(40) NOT NULL COMMENT 'NOT_REQUIRED / PENDING / SENDING / SIMULATED / SENT / FAILED / SKIPPED_*',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CONFIRMED / REVOKED',
    key_frame_path VARCHAR(255) NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_alert_recognition_type (recognition_id, record_type),
    KEY idx_alert_vehicle_time (vehicle_id, captured_at),
    KEY idx_alert_driver_time (driver_id, captured_at),
    KEY idx_alert_sms (sms_status)
) COMMENT '告警与速度未知异常';

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
