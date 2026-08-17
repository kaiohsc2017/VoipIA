-- =============================================================================
-- V89: Quality Management Avançado — Contestações de Avaliação e Planos de Coaching
-- Permite que o agente conteste notas de avaliação de chamada diretamente pelo
-- Desktop e vincula Planos de Ação / Coaching aos itens de ficha com notas baixas.
-- =============================================================================

-- ─── 1. cc_evaluation_appeals — Contestações de Avaliação pelo Agente ────────
CREATE TABLE cc_evaluation_appeals (
    id BIGSERIAL PRIMARY KEY,
    evaluation_id BIGINT NOT NULL REFERENCES call_evaluations(id) ON DELETE CASCADE,
    agent_id BIGINT NOT NULL REFERENCES cc_agents(id) ON DELETE CASCADE,
    interaction_id BIGINT REFERENCES cc_interactions(id) ON DELETE SET NULL,
    reason TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDENTE',
    supervisor_notes TEXT,
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cc_evaluation_appeals_status CHECK (status IN ('PENDENTE', 'APROVADA', 'REJEITADA', 'CANCELADA'))
);

COMMENT ON TABLE cc_evaluation_appeals IS 'Contestações abertas por agentes sobre notas de avaliação de chamadas (Quality Management)';
COMMENT ON COLUMN cc_evaluation_appeals.reason IS 'Justificativa e argumentação do agente para solicitar revisão da avaliação';
COMMENT ON COLUMN cc_evaluation_appeals.status IS 'Status da contestação: PENDENTE, APROVADA, REJEITADA, CANCELADA';
COMMENT ON COLUMN cc_evaluation_appeals.supervisor_notes IS 'Parecer do supervisor/avaliador que analisou a contestação';

CREATE INDEX idx_cc_evaluation_appeals_agent ON cc_evaluation_appeals(agent_id, created_at DESC);
CREATE INDEX idx_cc_evaluation_appeals_eval ON cc_evaluation_appeals(evaluation_id);
CREATE INDEX idx_cc_evaluation_appeals_status ON cc_evaluation_appeals(status);

-- ─── 2. cc_agent_coaching_plans — Planos de Ação e Coaching do Agente ────────
CREATE TABLE cc_agent_coaching_plans (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES cc_agents(id) ON DELETE CASCADE,
    scorecard_item_id BIGINT REFERENCES scorecard_items(id) ON DELETE SET NULL,
    evaluation_id BIGINT REFERENCES call_evaluations(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    action_items JSONB,
    target_score NUMERIC(5,2),
    status VARCHAR(30) NOT NULL DEFAULT 'EM_ANDAMENTO',
    deadline DATE,
    created_by VARCHAR(100) DEFAULT 'SYSTEM',
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cc_coaching_plans_status CHECK (status IN ('EM_ANDAMENTO', 'CONCLUIDO', 'CANCELADO'))
);

COMMENT ON TABLE cc_agent_coaching_plans IS 'Planos de ação e coaching vinculados a critérios de qualidade e metas do agente';
COMMENT ON COLUMN cc_agent_coaching_plans.action_items IS 'Array JSON com diretrizes, passos práticos ou checklists de evolução do agente';
COMMENT ON COLUMN cc_agent_coaching_plans.status IS 'Status do plano: EM_ANDAMENTO, CONCLUIDO, CANCELADO';

CREATE INDEX idx_cc_coaching_plans_agent ON cc_agent_coaching_plans(agent_id, status);
CREATE INDEX idx_cc_coaching_plans_deadline ON cc_agent_coaching_plans(deadline);

-- ─── 3. Triggers para atualização automática do updated_at ───────────────────
CREATE OR REPLACE FUNCTION update_cc_qm_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cc_evaluation_appeals_updated_at
    BEFORE UPDATE ON cc_evaluation_appeals
    FOR EACH ROW EXECUTE FUNCTION update_cc_qm_timestamp();

CREATE TRIGGER trg_cc_agent_coaching_plans_updated_at
    BEFORE UPDATE ON cc_agent_coaching_plans
    FOR EACH ROW EXECUTE FUNCTION update_cc_qm_timestamp();
