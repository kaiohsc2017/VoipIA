"""database.py — conexão e schema PostgreSQL da plataforma de agentes"""
import asyncpg, os
from typing import AsyncGenerator

_pool: asyncpg.Pool | None = None

def _dsn() -> str:
    return (
        os.environ.get("AGENTS_DATABASE_URL")
        or f"postgresql://{os.environ.get('AGENTS_DB_USER','agents')}:"
           f"{os.environ.get('AGENTS_DB_PASS','agents')}@"
           f"{os.environ.get('AGENTS_DB_HOST','agents-postgres')}:"
           f"{os.environ.get('AGENTS_DB_PORT','5432')}/"
           f"{os.environ.get('AGENTS_DB_NAME','agentsdb')}"
    )

SCHEMA = """
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Servidores SSH alvo
CREATE TABLE IF NOT EXISTS servers (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT NOT NULL,
    host        TEXT NOT NULL,
    port        INT  DEFAULT 22,
    username    TEXT NOT NULL,
    auth_type   TEXT NOT NULL DEFAULT 'password',  -- 'password' | 'key'
    password    TEXT,
    ssh_key     TEXT,
    tags        TEXT[] DEFAULT '{}',
    active      BOOLEAN DEFAULT true,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Agentes
CREATE TABLE IF NOT EXISTS agents (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT NOT NULL,
    description TEXT,
    type        TEXT NOT NULL,   -- 'ssh_test' | 'web_monitor' | 'log_monitor'
    skill       TEXT NOT NULL,   -- contexto/prompt do agente
    server_ids  UUID[] DEFAULT '{}',
    target_urls TEXT[] DEFAULT '{}',
    rules       JSONB  DEFAULT '{}',
    schedule    JSONB  DEFAULT '{}',
    -- schedule: {type: 'interval'|'cron'|'always', value: '5m'|'0 * * * *', active: true}
    notify_telegram BOOLEAN DEFAULT false,
    telegram_chat   TEXT,
    status      TEXT DEFAULT 'idle',  -- 'idle' | 'running' | 'error' | 'paused'
    last_run    TIMESTAMPTZ,
    next_run    TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Execuções
CREATE TABLE IF NOT EXISTS executions (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    agent_id    UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    session_id  TEXT NOT NULL,
    status      TEXT DEFAULT 'running',   -- 'running'|'success'|'partial'|'error'
    started_at  TIMESTAMPTZ DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    duration_s  FLOAT,
    total_checks   INT DEFAULT 0,
    passed_checks  INT DEFAULT 0,
    failed_checks  INT DEFAULT 0,
    summary     TEXT,
    report_json JSONB DEFAULT '{}'
);

-- Logs de execução (linha por linha)
CREATE TABLE IF NOT EXISTS execution_logs (
    id          BIGSERIAL PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
    agent_id    UUID NOT NULL,
    ts          TIMESTAMPTZ DEFAULT NOW(),
    level       TEXT DEFAULT 'info',   -- 'info'|'success'|'warning'|'error'
    server      TEXT,
    message     TEXT NOT NULL,
    raw_output  TEXT
);

-- Memória individual por agente (RAG via pg_trgm)
CREATE TABLE IF NOT EXISTS agent_memory (
    id          BIGSERIAL PRIMARY KEY,
    agent_id    UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    mtype       TEXT NOT NULL,  -- 'fix'|'pattern'|'state'|'preference'|'observation'
    title       TEXT NOT NULL,
    content     TEXT NOT NULL,
    metadata    JSONB DEFAULT '{}',
    tags        TEXT[] DEFAULT '{}',
    usefulness  INT DEFAULT 0,  -- votos de utilidade entre agentes
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Base de conhecimento (PDFs indexados)
CREATE TABLE IF NOT EXISTS knowledge_docs (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    filename    TEXT NOT NULL,
    title       TEXT,
    content     TEXT NOT NULL,   -- texto extraído do PDF
    tags        TEXT[] DEFAULT '{}',
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Alertas enviados
CREATE TABLE IF NOT EXISTS alerts (
    id          BIGSERIAL PRIMARY KEY,
    agent_id    UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    execution_id UUID REFERENCES executions(id),
    channel     TEXT NOT NULL,   -- 'telegram'|'web'
    level       TEXT NOT NULL,   -- 'info'|'warning'|'error'|'critical'
    message     TEXT NOT NULL,
    sent_at     TIMESTAMPTZ DEFAULT NOW(),
    delivered   BOOLEAN DEFAULT false
);

-- Índices
CREATE INDEX IF NOT EXISTS idx_executions_agent  ON executions (agent_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_logs_execution    ON execution_logs (execution_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_logs_agent        ON execution_logs (agent_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_memory_agent      ON agent_memory (agent_id, mtype);
CREATE INDEX IF NOT EXISTS idx_memory_content    ON agent_memory USING gin(content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_knowledge_content ON knowledge_docs USING gin(content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_alerts_agent      ON alerts (agent_id, sent_at DESC);
"""

async def init_db():
    global _pool
    _pool = await asyncpg.create_pool(_dsn(), min_size=2, max_size=10)
    async with _pool.acquire() as conn:
        await conn.execute(SCHEMA)

async def get_db() -> asyncpg.Connection:
    return await _pool.acquire()

async def release_db(conn):
    await _pool.release(conn)

class DB:
    """Context manager para conexão do pool."""
    async def __aenter__(self):
        self.conn = await _pool.acquire()
        return self.conn
    async def __aexit__(self, *_):
        await _pool.release(self.conn)
