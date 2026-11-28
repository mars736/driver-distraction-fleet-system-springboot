CREATE TABLE IF NOT EXISTS transport_task_dispatch_rating (
    task_id BIGINT PRIMARY KEY,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    score DECIMAL(4,1) NOT NULL,
    level CHAR(1) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_as_of DATETIME(3) NOT NULL,
    published_at DATETIME(3) NOT NULL,
    has_driving_history BOOLEAN NOT NULL,
    CONSTRAINT fk_task_dispatch_rating_task FOREIGN KEY (task_id) REFERENCES transport_task(id) ON DELETE CASCADE
) COMMENT '高要求订单确认派发时使用的长期安全评级';
