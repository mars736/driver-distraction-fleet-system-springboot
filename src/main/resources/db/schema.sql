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

-- ==================== M0 档案模块 ====================

CREATE TABLE IF NOT EXISTS company (
                                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                       company_no VARCHAR(32) NOT NULL COMMENT '车队编号',
    name VARCHAR(64) NOT NULL COMMENT '车队名称',
    manager VARCHAR(32) NULL COMMENT '负责人',
    phone VARCHAR(20) NULL COMMENT '联系电话',
    established_date DATE NULL COMMENT '成立时间',
    remark VARCHAR(255) NULL COMMENT '备注',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1 启用，0 停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_company_no (company_no)
    ) COMMENT '车队';

CREATE TABLE IF NOT EXISTS vehicle (
                                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                       company_id BIGINT NOT NULL COMMENT '所属车队',
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
    KEY idx_company_id (company_id)
    ) COMMENT '车辆';

CREATE TABLE IF NOT EXISTS driver (
                                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                      company_id BIGINT NOT NULL COMMENT '所属车队',
                                      employee_no VARCHAR(32) NOT NULL COMMENT '工号',
    name VARCHAR(32) NOT NULL COMMENT '姓名',
    phone VARCHAR(20) NOT NULL COMMENT '手机号',
    password VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    vehicle_id BIGINT NULL COMMENT '绑定车辆',
    account_status TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1 启用，0 禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_company_employee (company_id, employee_no),
    KEY idx_vehicle_id (vehicle_id)
    ) COMMENT '司机';

CREATE TABLE IF NOT EXISTS admin_user (
                                          id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                          company_id BIGINT NULL COMMENT '所属车队；平台管理员为空',
                                          username VARCHAR(32) NOT NULL COMMENT '登录名',
    password VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    real_name VARCHAR(32) NULL COMMENT '真实姓名',
    role VARCHAR(32) NOT NULL COMMENT 'PLATFORM_ADMIN / FLEET_ADMIN',
    status TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1 启用，0 禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    KEY idx_company_id (company_id)
    ) COMMENT '管理员账号';
