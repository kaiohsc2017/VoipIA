-- =============================================================================
-- V14__agents_schema.sql — Schema da Plataforma de Agentes
-- Criado pelo migrate_db() (Python/asyncpg) nas versões anteriores via
-- CREATE TABLE IF NOT EXISTS. Esta migration registra formalmente o schema
-- no controle de versão Flyway, garantindo rastreabilidade e ordem de aplicação.
-- As tabelas já existem em produção — todos os statements são idempotentes.
-- =============================================================================

-- Extensões necessárias
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Servidores SSH alvo
CREATE TABLE IF NOT EXISTS servers (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT NOT NULL,
    host        TEXT NOT NULL,
    port        INT  DEFAULT 22,
    username    TEXT NOT NULL,
    auth_type   TEXT NOT NULL DEFAULT 'password',
    password    TEXT,
    ssh_key     TEXT,
    tags        TEXT[] DEFAULT '{}',
    active      BOOLEAN DEFAULT true,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Agentes de automação
CREATE TABLE IF NOT EXISTS agents (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT NOT NULL,
    description TEXT,
    type        TEXT NOT NULL,
    skill       TEXT NOT NULL,
    server_ids  UUID[] DEFAULT '{}',
    target_urls TEXT[] DEFAULT '{}',
    rules       JSONB  DEFAULT '{}',
    schedule    JSONB  DEFAULT '{}',
    schedules   JSONB[] DEFAULT '{}',
    notify_telegram             BOOLEAN DEFAULT false,
    telegram_chat               TEXT,
    notify_email                BOOLEAN DEFAULT false,
    notify_email_to             TEXT,
    notify_webhook              BOOLEAN DEFAULT false,
    notify_webhook_url          TEXT,
    on_failure_trigger_agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    status      TEXT DEFAULT 'idle',
    last_run    TIMESTAMPTZ,
    next_run    TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Segredos por agente (variáveis sensíveis usadas nos checks)
CREATE TABLE IF NOT EXISTS agent_secrets (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    agent_id    UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    key         TEXT NOT NULL,
    value       TEXT NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(agent_id, key)
);

-- Execuções
CREATE TABLE IF NOT EXISTS executions (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    agent_id       UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    session_id     TEXT NOT NULL,
    status         TEXT DEFAULT 'running',
    started_at     TIMESTAMPTZ DEFAULT NOW(),
    finished_at    TIMESTAMPTZ,
    duration_s     FLOAT,
    total_checks   INT DEFAULT 0,
    passed_checks  INT DEFAULT 0,
    failed_checks  INT DEFAULT 0,
    summary        TEXT,
    report_json    JSONB DEFAULT '{}'
);

-- Logs de execução (linha por linha)
CREATE TABLE IF NOT EXISTS execution_logs (
    id           BIGSERIAL PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
    agent_id     UUID NOT NULL,
    ts           TIMESTAMPTZ DEFAULT NOW(),
    level        TEXT DEFAULT 'info',
    server       TEXT,
    message      TEXT NOT NULL,
    raw_output   TEXT
);

-- Memória individual por agente (RAG via pg_trgm)
CREATE TABLE IF NOT EXISTS agent_memory (
    id         BIGSERIAL PRIMARY KEY,
    agent_id   UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    mtype      TEXT NOT NULL,
    title      TEXT NOT NULL,
    content    TEXT NOT NULL,
    metadata   JSONB DEFAULT '{}',
    tags       TEXT[] DEFAULT '{}',
    usefulness INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Base de conhecimento (PDFs indexados)
CREATE TABLE IF NOT EXISTS knowledge_docs (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    filename   TEXT NOT NULL,
    title      TEXT,
    content    TEXT NOT NULL,
    tags       TEXT[] DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Alertas enviados
CREATE TABLE IF NOT EXISTS alerts (
    id           BIGSERIAL PRIMARY KEY,
    agent_id     UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    execution_id UUID REFERENCES executions(id),
    channel      TEXT NOT NULL,
    level        TEXT NOT NULL,
    message      TEXT NOT NULL,
    sent_at      TIMESTAMPTZ DEFAULT NOW(),
    delivered    BOOLEAN DEFAULT false
);

-- Configuração de retenção de dados
CREATE TABLE IF NOT EXISTS retention_config (
    id               INT PRIMARY KEY DEFAULT 1,
    executions_days  INT DEFAULT 90,
    logs_days        INT DEFAULT 30,
    alerts_days      INT DEFAULT 180,
    updated_at       TIMESTAMPTZ DEFAULT NOW()
);
INSERT INTO retention_config (id, executions_days, logs_days, alerts_days)
VALUES (1, 90, 30, 180)
ON CONFLICT (id) DO NOTHING;

-- Índices
CREATE INDEX IF NOT EXISTS idx_executions_agent  ON executions (agent_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_executions_status ON executions (status, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_logs_execution    ON execution_logs (execution_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_logs_agent        ON execution_logs (agent_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_memory_agent      ON agent_memory (agent_id, mtype);
CREATE INDEX IF NOT EXISTS idx_memory_content    ON agent_memory USING gin(content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_knowledge_content ON knowledge_docs USING gin(content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_alerts_agent      ON alerts (agent_id, sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_agents_next_run   ON agents (next_run) WHERE status != 'paused';
