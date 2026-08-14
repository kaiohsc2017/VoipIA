-- V73 — Sub-fase 17a do módulo Call Center (co-browsing gravado do chat, plano
-- callcenter-fase17-cobrowsing.plan.md, §3). Escopo desta fatia: só o modelo de dados, o toggle
-- por agente, e o registro de consentimento do cliente — SEM captura real de eventos rrweb
-- (fica pra 17b), sem player (17c), sem expurgo automatizado por retenção (17d).
--
-- Reaproveitamento deliberado: cc_cobrowse_sessions.chat_session_id aponta 1:1 pra
-- cc_chat_sessions (UNIQUE — no máximo uma sessão de cobrowse por conversa de chat), mesmo
-- padrão de desnormalização de business_unit_id usado em cc_chat_sessions/cc_recordings
-- (herdada no momento da criação, nunca recalculada depois).

CREATE TABLE cc_cobrowse_sessions (
    id                BIGSERIAL PRIMARY KEY,
    chat_session_id   BIGINT NOT NULL UNIQUE REFERENCES cc_chat_sessions(id) ON DELETE CASCADE,
    business_unit_id  BIGINT REFERENCES business_units(id),
    consent_status    VARCHAR(20) NOT NULL DEFAULT 'pending'
                          CHECK (consent_status IN ('pending', 'granted', 'denied', 'revoked')),
    consent_at        TIMESTAMPTZ,
    consent_text_hash VARCHAR(64),
    revoked_at        TIMESTAMPTZ,
    file_path         VARCHAR(255),
    size_bytes        BIGINT NOT NULL DEFAULT 0,
    event_count       INTEGER NOT NULL DEFAULT 0,
    truncated         BOOLEAN NOT NULL DEFAULT false,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_event_at     TIMESTAMPTZ,
    purged_at         TIMESTAMPTZ
);

COMMENT ON TABLE cc_cobrowse_sessions IS 'Co-browsing gravado do chat (Fase 17) — nesta fatia (17a) só o registro de consentimento; file_path/size_bytes/event_count/truncated ficam zerados/nulos até a captura real (17b) existir';
COMMENT ON COLUMN cc_cobrowse_sessions.business_unit_id IS 'Herdada de cc_chat_sessions.business_unit_id no momento da criação — mesmo padrão de cc_chat_sessions.business_unit_id';
COMMENT ON COLUMN cc_cobrowse_sessions.consent_text_hash IS 'SHA-256 (hex, 64 chars) do texto de consentimento exibido ao cliente no momento do aceite/recusa — nunca o texto em si';
COMMENT ON COLUMN cc_cobrowse_sessions.purged_at IS 'Marcado quando o consentimento é revogado — nesta fatia não existe arquivo físico a apagar ainda (17b), só o registro do estado';

CREATE INDEX idx_cc_cobrowse_started_at ON cc_cobrowse_sessions(started_at);
CREATE INDEX idx_cc_cobrowse_bu ON cc_cobrowse_sessions(business_unit_id);

ALTER TABLE cc_agents ADD COLUMN cobrowse_enabled BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN cc_agents.cobrowse_enabled IS 'Toggle por agente (D17-14) — se true, ao assumir um chat uma cc_cobrowse_sessions é criada automaticamente (sujeita ao consentimento do cliente); default false';

CREATE TABLE cc_cobrowse_retention_config (
    id                        VARCHAR(20) PRIMARY KEY DEFAULT 'default',
    retention_days            INTEGER NOT NULL DEFAULT 1826,
    last_purge_at             TIMESTAMPTZ,
    last_purge_deleted_count  INTEGER,
    updated_by                VARCHAR(120)
);

COMMENT ON TABLE cc_cobrowse_retention_config IS 'Configuração de retenção do co-browsing (60 meses default, igual à voz) — scheduler de expurgo é trabalho da fatia 17d; a tabela já existe para não exigir migration nova quando 17d chegar';

INSERT INTO cc_cobrowse_retention_config (id) VALUES ('default') ON CONFLICT DO NOTHING;
