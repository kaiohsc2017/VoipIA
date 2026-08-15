-- V86 — Fase 16 do plano-mãe do Call Center (Histórico do contato e copiloto de IA para o
-- agente). Depende da Fase 14 (identidade resolvida) já entregue na V85.

-- Perfil traçado por IA para um contato identificado (resolved_ad_sam) — geração assíncrona,
-- nunca bloqueia o atendimento (D16.2). Reaproveitado por até app.callcenter.copiloto.cache-hours
-- (default 24h) antes de regerar, principal controle de custo desta fase.
CREATE TABLE cc_contact_profiles (
    id BIGSERIAL PRIMARY KEY,
    resolved_ad_sam VARCHAR(128) NOT NULL,
    interaction_id BIGINT REFERENCES cc_interactions(id),
    profile_json JSONB NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT now(),
    model VARCHAR(60),
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    cost_usd NUMERIC(12, 6) NOT NULL DEFAULT 0
);

-- Busca do perfil mais recente de um contato, mais recente primeiro — índice cobre o caso de
-- uso real (checar se já existe um fresco antes de gerar de novo).
CREATE INDEX idx_cc_contact_profiles_sam_generated ON cc_contact_profiles(resolved_ad_sam, generated_at DESC);

-- Feedback do agente por ação sugerida (útil/não útil, Fase 16.3) — matéria-prima para ajustar
-- o prompt depois; sem FK para cc_agents.id ser obrigatória (log de qual agente deu o feedback é
-- só informativo, nunca bloqueia o registro se o agente foi removido).
CREATE TABLE cc_contact_profile_feedback (
    id BIGSERIAL PRIMARY KEY,
    profile_id BIGINT NOT NULL REFERENCES cc_contact_profiles(id),
    action_index INT NOT NULL,
    useful BOOLEAN NOT NULL,
    agent_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_cc_contact_profile_feedback_profile_id ON cc_contact_profile_feedback(profile_id);

-- Frente de custo "callcenter_copiloto" no Financeiro (mesmo padrão de V65/V69/V85) — é a frente
-- com o pior perfil de custo do módulo (dispara por contato, não por gravação processada), por
-- isso o alerta de gasto é obrigatório desde o dia 1 (§5.1 do plano-mãe).
ALTER TABLE financeiro_cost_alerts DROP CONSTRAINT chk_financeiro_cost_alerts_scope;
ALTER TABLE financeiro_cost_alerts
    ADD CONSTRAINT chk_financeiro_cost_alerts_scope
        CHECK (scope IN ('ura', 'insights', 'envios', 'callcenter', 'callcenter_nps',
                          'callcenter_autosservico', 'callcenter_identidade', 'callcenter_copiloto'));

INSERT INTO financeiro_cost_alerts (scope, threshold_usd, enabled) VALUES ('callcenter_copiloto', 0, FALSE);
