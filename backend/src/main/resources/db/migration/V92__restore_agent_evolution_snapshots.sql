-- =============================================================================
-- V92__restore_agent_evolution_snapshots.sql
-- Restaura a tabela agent_evolution_snapshots do modulo Insights (Quality Management),
-- utilizada pelo AgentReportService para rastreamento de metricas temporais.
-- =============================================================================

CREATE TABLE IF NOT EXISTS agent_evolution_snapshots (
    id          BIGSERIAL PRIMARY KEY,
    agent_name  VARCHAR(200) NOT NULL,
    report_id   BIGINT NOT NULL REFERENCES agent_performance_reports(id) ON DELETE CASCADE,
    item_id     BIGINT REFERENCES scorecard_items(id),
    metric_key  VARCHAR(100) NOT NULL,
    valor       NUMERIC(10,2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE agent_evolution_snapshots IS 'Ponto de serie temporal por agente/metrica — permite ao supervisor navegar o historico de evolucao do agente';

CREATE INDEX IF NOT EXISTS idx_agent_evolution_snapshots_agent ON agent_evolution_snapshots(agent_name, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_evolution_snapshots_report ON agent_evolution_snapshots(report_id);

-- app_users.sso_linked — mesmo racional de ad_linked: só uma conta explicitamente provisionada
-- via SSO pode ser autenticada pelo fluxo SSO, evitando sequestro de conta local por colisão
-- de e-mail/username com uma identidade do tenant Microsoft Entra ID. Colocada aqui (V92) e não
-- na V91 porque a V91 já havia sido aplicada em produção antes desta correção — editá-la
-- quebraria o checksum do Flyway.
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS sso_linked BOOLEAN NOT NULL DEFAULT FALSE;
