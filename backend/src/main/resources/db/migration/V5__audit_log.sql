-- V5__audit_log.sql
-- Tabela de auditoria de ações do sistema (Fase 13)

CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    username        VARCHAR(64),
    ip_address      VARCHAR(45),
    action          VARCHAR(64)  NOT NULL,  -- LOGIN, LOGIN_FAILED, LOGOUT, SETTINGS_CHANGE, USER_CREATE, USER_UPDATE, USER_DELETE, EXPORT, RATE_LIMIT_BLOCKED
    details         TEXT,                   -- Detalhes adicionais (chave alterada, usuário criado, etc.)
    success         BOOLEAN      NOT NULL DEFAULT TRUE,
    user_agent      VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_username   ON audit_logs (username);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action     ON audit_logs (action);
