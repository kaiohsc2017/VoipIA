-- V56 — Sub-fase 7a do módulo Call Center (base interna do canal de chat, plano
-- modulo-callcenter-omnicanal.plan.md, Fase 7). Escopo decidido com o usuário: SEM widget
-- público exposto à internet ainda — isso é a fatia 7b, que vai desenhar com calma um esquema
-- de autenticação anônima pro cliente final (diferente do JWT de ramal usado hoje no
-- WebSocket/API). Aqui só o modelo de dados, o roteamento interno (fila → agente) e um
-- simulador de cliente restrito a ADMIN pra validar o pipeline ponta a ponta.
--
-- Reaproveitamento deliberado: cc_chat_sessions.queue_id aponta direto pra cc_queues — não
-- criamos uma fila paralela só pra chat, a mesma fila de voz também roteia chat (discriminado
-- pelo canal da sessão). cc_chat_sessions.disposition_id aponta pro catálogo global
-- cc_dispositions (Fase 4) — mesma tabulação usada em interações de voz.

CREATE TABLE cc_chat_channels (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(30) UNIQUE NOT NULL,
    display_name VARCHAR(150) NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_chat_channels IS 'Canal de origem de uma sessão de chat (webchat/WhatsApp/Telegram na Fase 7b — nesta fatia só o simulador interno)';

INSERT INTO cc_chat_channels (code, display_name, active)
VALUES ('internal_test', 'Simulador interno (Fase 7a — sem widget público ainda)', true);

CREATE TABLE cc_chat_sessions (
    id                BIGSERIAL PRIMARY KEY,
    channel_id        BIGINT NOT NULL REFERENCES cc_chat_channels(id),
    queue_id          BIGINT NOT NULL REFERENCES cc_queues(id),
    business_unit_id  BIGINT REFERENCES business_units(id),
    customer_ref      VARCHAR(120) NOT NULL,
    customer_name     VARCHAR(150),
    status            VARCHAR(20) NOT NULL DEFAULT 'waiting' CHECK (status IN ('waiting', 'active', 'closed')),
    assigned_agent_id BIGINT REFERENCES cc_agents(id),
    disposition_id    BIGINT REFERENCES cc_dispositions(id),
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at        TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ
);

COMMENT ON TABLE cc_chat_sessions IS 'Uma conversa de chat, da entrada na fila até o encerramento — equivalente chat de cc_interactions (que é estritamente de voz, tem channel_uniqueid do Asterisk)';
COMMENT ON COLUMN cc_chat_sessions.customer_ref IS 'Identificador opaco do contato — nesta fatia é texto livre digitado no simulador; na Fase 7b vira o identificador real do canal (telefone/WhatsApp id/etc)';
COMMENT ON COLUMN cc_chat_sessions.business_unit_id IS 'Herdada da fila (cc_queues.business_unit) no momento da criação da sessão — mesmo padrão de cc_recordings.business_unit';

CREATE INDEX idx_cc_chat_sessions_status_queue ON cc_chat_sessions(status, queue_id);
CREATE INDEX idx_cc_chat_sessions_assigned_agent ON cc_chat_sessions(assigned_agent_id);

CREATE TABLE cc_chat_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT NOT NULL REFERENCES cc_chat_sessions(id) ON DELETE CASCADE,
    sender_type VARCHAR(10) NOT NULL CHECK (sender_type IN ('customer', 'agent', 'system')),
    sender_name VARCHAR(150),
    body        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_chat_messages IS 'Mensagens de uma sessão de chat, em ordem cronológica';

CREATE INDEX idx_cc_chat_messages_session ON cc_chat_messages(session_id, created_at);

CREATE TABLE cc_canned_responses (
    id                BIGSERIAL PRIMARY KEY,
    title             VARCHAR(150) NOT NULL,
    body              TEXT NOT NULL,
    category          VARCHAR(80),
    business_unit_id  BIGINT REFERENCES business_units(id),
    active            BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_canned_responses IS 'Biblioteca de respostas rápidas do agente no chat — catálogo compartilhado, sem posse individual (mesmo padrão de cc_dispositions)';
