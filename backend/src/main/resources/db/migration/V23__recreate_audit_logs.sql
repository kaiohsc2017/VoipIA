-- V23: recria a tabela audit_logs.
--
-- Achado: a V5__audit_log.sql está registrada no flyway_schema_history como
-- aplicada com sucesso (mesmo timestamp idêntico e execution_time=0 que as
-- demais V1-V10 — assinatura de um `flyway baseline`/restore de schema, não
-- de execução real linha a linha), mas a tabela audit_logs não existe no
-- banco de produção. Toda gravação de auditoria (login, criação/edição de
-- usuário, mudança de configuração) vinha falhando silenciosamente desde
-- então — AuditService.persist() engole a exceção de propósito pra nunca
-- derrubar a requisição principal, então ninguém percebeu.
--
-- Como V5 já está marcada como aplicada, o Flyway nunca vai re-executá-la —
-- daí a migration nova. Mesma estrutura exata de V5, mantendo compat com a
-- entidade AuditLog.java (nada mudou nela desde então).

CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    username        VARCHAR(64),
    ip_address      VARCHAR(45),
    action          VARCHAR(64)  NOT NULL,
    details         TEXT,
    success         BOOLEAN      NOT NULL DEFAULT TRUE,
    user_agent      VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_username   ON audit_logs (username);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action     ON audit_logs (action);
