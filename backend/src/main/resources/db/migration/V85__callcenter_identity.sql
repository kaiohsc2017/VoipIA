-- V85 — Fase 14 do Call Center (Identidade do contato e screen pop).
--
-- pg_trgm já é usada desde a V14 (memória de agentes) — reafirmada aqui com IF NOT EXISTS por
-- clareza de dependência desta fase (busca aproximada de nome falado contra ad_users).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Resultado da cascata de identificação (CallCenterIdentityResolver) fica gravado na própria
-- interação/sessão de chat — nunca recalculado a cada leitura da tela de Desktop do Agente.
-- identity_source espelha o enum IdentitySource (NETWORK_LOGIN|URA_INPUT|ANI|UNRESOLVED, mas
-- UNRESOLVED nunca é persistido — ausência de linha já significa isso).
ALTER TABLE cc_interactions ADD COLUMN resolved_ad_sam VARCHAR(128);
ALTER TABLE cc_interactions ADD COLUMN identity_source VARCHAR(20);

ALTER TABLE cc_chat_sessions ADD COLUMN resolved_ad_sam VARCHAR(128);
ALTER TABLE cc_chat_sessions ADD COLUMN identity_source VARCHAR(20);

-- Índices para a tela de "histórico de contatos anteriores" (Desktop do Agente) — busca por
-- resolved_ad_sam, nunca varredura completa.
CREATE INDEX idx_cc_interactions_resolved_ad_sam ON cc_interactions(resolved_ad_sam);
CREATE INDEX idx_cc_chat_sessions_resolved_ad_sam ON cc_chat_sessions(resolved_ad_sam);

-- Índices trigram para a busca aproximada por nome falado (STT) contra ad_users — display_name
-- é o campo mais próximo do que um cliente fala ("João da Silva"); sam_account_name também
-- indexado para a via de confirmação por login digitado/falado com pequeno erro de transcrição.
CREATE INDEX idx_ad_users_display_name_trgm ON ad_users USING gin (display_name gin_trgm_ops);
CREATE INDEX idx_ad_users_sam_account_name_trgm ON ad_users USING gin (sam_account_name gin_trgm_ops);

-- Log de custo de IA da transcrição de identificação por voz (STT do login/nome falado +
-- confirmação) — mesmo padrão de cc_survey_responses.ai_cost_usd/cc_kb_answer_log, tabela
-- própria só para não sobrecarregar cc_interactions com uma coluna de custo que não é 1:1 (uma
-- interação pode ter 0, 1 ou 2 chamadas de STT — coleta + confirmação).
CREATE TABLE cc_identity_resolution_log (
    id BIGSERIAL PRIMARY KEY,
    resolved_at TIMESTAMP NOT NULL DEFAULT now(),
    channel VARCHAR(10) NOT NULL, -- voice|chat
    outcome VARCHAR(20) NOT NULL, -- resolved|unresolved|rejected (cliente disse "não" na confirmação)
    ai_cost_usd NUMERIC(12, 6) NOT NULL DEFAULT 0
);

CREATE INDEX idx_cc_identity_resolution_log_resolved_at ON cc_identity_resolution_log(resolved_at);

-- Frente de custo "callcenter_identidade" no Financeiro (mesmo padrão de V65/V69) — scope já
-- VARCHAR(40) desde a V69, cabe sem novo ALTER de tipo.
ALTER TABLE financeiro_cost_alerts DROP CONSTRAINT chk_financeiro_cost_alerts_scope;
ALTER TABLE financeiro_cost_alerts
    ADD CONSTRAINT chk_financeiro_cost_alerts_scope
        CHECK (scope IN ('ura', 'insights', 'envios', 'callcenter', 'callcenter_nps',
                          'callcenter_autosservico', 'callcenter_identidade'));

INSERT INTO financeiro_cost_alerts (scope, threshold_usd, enabled) VALUES ('callcenter_identidade', 0, FALSE);
