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
    username VARCHAR(64) NOT NULL COMMENT '登录账号，司机=手机号，管理员=admin',
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
