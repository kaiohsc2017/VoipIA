-- Fase 7d do plano de fechamento 5/7/9 do Call Center — anexos bidirecionais no chat, allowlist
-- de extensão configurável e cota/retenção por usuário (D6, confirmado com o usuário).
--
-- cc_chat_attachments referencia cc_chat_sessions (tabela normal, não particionada) — nunca
-- cc_chat_messages(id) isolado, porque cc_chat_messages é particionada por created_at (V71) e o
-- Postgres não permite FK para uma coluna que não seja PK/UNIQUE isolada numa tabela particionada
-- (mesma restrição já documentada na V72, cc_flow_execution_steps x cc_flow_executions). Um anexo
-- é tratado como um evento próprio da sessão, não uma linha de cc_chat_messages.

ALTER TABLE cc_chat_channels
    ADD COLUMN attachment_quota_bytes BIGINT NOT NULL DEFAULT 2147483648,
    ADD COLUMN attachment_retention_days INTEGER NOT NULL DEFAULT 10;

ALTER TABLE cc_chat_channels
    ADD CONSTRAINT chk_cc_chat_channels_attachment_quota CHECK (attachment_quota_bytes > 0);
ALTER TABLE cc_chat_channels
    ADD CONSTRAINT chk_cc_chat_channels_attachment_retention CHECK (attachment_retention_days > 0);

-- Catálogo de extensões aceitas — cadastrado extensão por extensão pelo operador (D6). Vazio por
-- padrão: nenhum upload é aceito até o operador cadastrar ao menos uma extensão.
CREATE TABLE cc_chat_attachment_extensions (
    id         BIGSERIAL PRIMARY KEY,
    extension  VARCHAR(20) NOT NULL UNIQUE,
    mimetype   VARCHAR(150),
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cc_chat_attachments (
    id                   BIGSERIAL PRIMARY KEY,
    session_id           BIGINT NOT NULL REFERENCES cc_chat_sessions(id) ON DELETE CASCADE,
    sender_type          VARCHAR(10) NOT NULL CHECK (sender_type IN ('customer', 'agent')),
    sender_name          VARCHAR(150),
    -- Identidade estável para cota/diretório (username do agente autenticado, ou
    -- "cliente-<customerRef sanitizado>" para o cliente) — nunca o nome de exibição, que pode
    -- mudar sem preservar a mesma pasta/cota.
    uploader_key         VARCHAR(150) NOT NULL,
    original_file_name   VARCHAR(255) NOT NULL,
    stored_relative_path VARCHAR(500) NOT NULL,
    content_type         VARCHAR(150),
    size_bytes           BIGINT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cc_chat_attachments_session ON cc_chat_attachments(session_id, created_at);
CREATE INDEX idx_cc_chat_attachments_uploader ON cc_chat_attachments(uploader_key, created_at);
