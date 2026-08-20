-- =============================================================================
-- V91: Enterprise Evolutions — WFM Preditivo, Busca Semântica, SSO Entra & Copiloto
-- =============================================================================

-- ─── 1. cc_queue_wfm_forecasts — Digital Twin & WFM Preditivo para Filas ──────
CREATE TABLE IF NOT EXISTS cc_queue_wfm_forecasts (
    id BIGSERIAL PRIMARY KEY,
    queue_id BIGINT NOT NULL REFERENCES cc_queues(id) ON DELETE CASCADE,
    forecast_timestamp TIMESTAMPTZ NOT NULL,
    interval_minutes INT NOT NULL DEFAULT 15,
    predicted_call_volume INT NOT NULL,
    predicted_aht_seconds INT NOT NULL,
    required_agents INT NOT NULL,
    current_scheduled_agents INT NOT NULL DEFAULT 0,
    predicted_sla_percent DOUBLE PRECISION NOT NULL,
    target_sla_percent DOUBLE PRECISION NOT NULL DEFAULT 80.0,
    sla_breach_risk BOOLEAN NOT NULL DEFAULT FALSE,
    algorithm VARCHAR(50) NOT NULL DEFAULT 'ERLANG_C_EWMA',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cc_wfm_forecast_queue ON cc_queue_wfm_forecasts(queue_id, forecast_timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_cc_wfm_forecast_risk ON cc_queue_wfm_forecasts(sla_breach_risk);

-- ─── 2. Busca Semântica de Gravações (pgvector) ─────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'call_records' AND column_name = 'embedding'
    ) THEN
        ALTER TABLE call_records ADD COLUMN embedding vector(384);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_call_records_embedding_hnsw 
ON call_records USING hnsw (embedding vector_cosine_ops)
WHERE embedding IS NOT NULL;

-- ─── 3. sso_configurations — SSO Corporativo (Microsoft Entra ID / OIDC) ────
CREATE TABLE IF NOT EXISTS sso_configurations (
    id BIGSERIAL PRIMARY KEY,
    provider_name VARCHAR(50) NOT NULL DEFAULT 'MICROSOFT_ENTRA',
    display_name VARCHAR(100) NOT NULL DEFAULT 'Microsoft 365 / Entra ID',
    client_id VARCHAR(255),
    client_secret VARCHAR(255),
    tenant_id VARCHAR(255),
    discovery_url VARCHAR(500),
    authorization_url VARCHAR(500),
    token_url VARCHAR(500),
    user_info_url VARCHAR(500),
    redirect_uri VARCHAR(500),
    default_access_group_id BIGINT REFERENCES access_groups(id) ON DELETE SET NULL,
    auto_provision_users BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- app_users.sso_linked — mesmo racional de ad_linked: só uma conta explicitamente provisionada
-- via SSO pode ser autenticada pelo fluxo SSO, evitando sequestro de conta local por colisão
-- de e-mail/username com uma identidade do tenant Microsoft Entra ID.
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS sso_linked BOOLEAN NOT NULL DEFAULT FALSE;

-- Seed default Entra ID template
INSERT INTO sso_configurations (
    provider_name, display_name, tenant_id, client_id, is_active, auto_provision_users
)
SELECT 'MICROSOFT_ENTRA', 'Microsoft Entra ID (Azure AD)', 'common', '', false, true
WHERE NOT EXISTS (SELECT 1 FROM sso_configurations WHERE provider_name = 'MICROSOFT_ENTRA');

-- ─── 4. cc_agent_copilot_logs — Copiloto Realtime no Desktop do Agente ───────
CREATE TABLE IF NOT EXISTS cc_agent_copilot_logs (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT REFERENCES cc_agents(id) ON DELETE SET NULL,
    interaction_id VARCHAR(100),
    customer_utterance TEXT NOT NULL,
    suggested_response TEXT NOT NULL,
    suggested_kb_article_id BIGINT REFERENCES cc_kb_articles(id) ON DELETE SET NULL,
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    agent_feedback VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cc_copilot_logs_agent ON cc_agent_copilot_logs(agent_id, created_at DESC);

-- ─── 5. Permissões RBAC para as novas funcionalidades ────────────────────────
INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT g.id, 'callcenter.wfm', true, true
FROM access_groups g
WHERE g.name ILIKE '%admin%' OR g.name ILIKE '%supervis%'
ON CONFLICT DO NOTHING;

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT g.id, 'callcenter.copilot', true, true
FROM access_groups g
WHERE g.name ILIKE '%admin%' OR g.name ILIKE '%supervis%' OR g.name ILIKE '%agent%' OR g.name ILIKE '%atend%'
ON CONFLICT DO NOTHING;

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT g.id, 'insights.semantic_search', true, true
FROM access_groups g
WHERE g.name ILIKE '%admin%' OR g.name ILIKE '%supervis%' OR g.name ILIKE '%qualidade%'
ON CONFLICT DO NOTHING;

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT g.id, 'admin.sso', true, true
FROM access_groups g
WHERE g.name ILIKE '%admin%'
ON CONFLICT DO NOTHING;
