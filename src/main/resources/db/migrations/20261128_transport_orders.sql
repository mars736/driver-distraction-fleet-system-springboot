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

SET @transport_order_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='transport_task' AND column_name='transport_order_id'),
    'SELECT 1',
    'ALTER TABLE transport_task
        ADD COLUMN transport_order_id BIGINT NULL,
        ADD COLUMN origin_address VARCHAR(255) NULL,
        ADD COLUMN destination_address VARCHAR(255) NULL,
        ADD COLUMN requirement VARCHAR(8) NOT NULL DEFAULT ''NORMAL'',
        ADD COLUMN order_dispatch_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
        ADD COLUMN dispatch_order_version BIGINT NULL,
        ADD UNIQUE KEY uk_transport_order_dispatch (transport_order_id, order_dispatch_key),
        ADD CONSTRAINT fk_transport_task_order FOREIGN KEY (transport_order_id) REFERENCES transport_order(id) ON DELETE RESTRICT'
);
PREPARE transport_order_stmt FROM @transport_order_ddl;
EXECUTE transport_order_stmt;
DEALLOCATE PREPARE transport_order_stmt;
