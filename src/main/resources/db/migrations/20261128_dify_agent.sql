CREATE TABLE IF NOT EXISTS dify_agent_draft (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    payload MEDIUMTEXT NULL,
    expires_at DATETIME(3) NOT NULL,
    result MEDIUMTEXT NULL,
    KEY idx_dify_agent_draft_expiry (expires_at)
) COMMENT 'Dify 管理助手的待确认草稿和幂等回执';
