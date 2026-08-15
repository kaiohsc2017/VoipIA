-- Fase A do nó "agente_ia" do Flow Builder (plano-mãe do Call Center): entidade cadastrável de
-- persona/prompt/modelo do Agente de IA, reutilizável entre nós/fluxos — o nó do grafo guarda só
-- o id (configuracaoIaId), nunca o prompt embutido no grafo.
CREATE TABLE cc_ia_agents (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    system_prompt TEXT NOT NULL,
    greeting TEXT,
    model VARCHAR(80) NOT NULL,
    temperature NUMERIC(3,2) NOT NULL DEFAULT 0.20
        CHECK (temperature >= 0 AND temperature <= 2),
    top_k INT
        CHECK (top_k >= 1 AND top_k <= 50),
    match_threshold NUMERIC(4,3)
        CHECK (match_threshold >= 0 AND match_threshold <= 1),
    kb_tags VARCHAR(500),
    max_turns INT NOT NULL DEFAULT 5
        CHECK (max_turns >= 1 AND max_turns <= 20),
    max_cost_usd NUMERIC(8,4) NOT NULL DEFAULT 0.10,
    fallback_queue_id BIGINT REFERENCES cc_queues(id) ON DELETE SET NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_cc_ia_agents_name ON cc_ia_agents (name);

-- Log de turnos (usado a partir da Fase B — pergunta/resposta/custo de cada rodada do laço do
-- nó agente_ia). Sem FK para cc_chat_sessions/canal de voz — correlation_ref guarda o channelId
-- ARI ou "chat-session-<id>" (mesmo padrão de parseSessionId em ConsultarBaseNodeHandler).
CREATE TABLE cc_ia_agent_turns (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES cc_ia_agents(id) ON DELETE CASCADE,
    channel VARCHAR(10) NOT NULL,
    correlation_ref VARCHAR(120),
    question TEXT,
    answer TEXT,
    matched BOOLEAN NOT NULL DEFAULT FALSE,
    model VARCHAR(80),
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    cost_usd NUMERIC(8,6) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_cc_ia_agent_turns_agent_created ON cc_ia_agent_turns (agent_id, created_at);

-- Frente de custo "callcenter_agente_ia" no Financeiro (mesmo padrão de V85/V86) — scope já
-- VARCHAR(40) desde a V69, cabe sem novo ALTER de tipo.
ALTER TABLE financeiro_cost_alerts DROP CONSTRAINT chk_financeiro_cost_alerts_scope;
ALTER TABLE financeiro_cost_alerts
    ADD CONSTRAINT chk_financeiro_cost_alerts_scope
        CHECK (scope IN ('ura', 'insights', 'envios', 'callcenter', 'callcenter_nps',
                          'callcenter_autosservico', 'callcenter_identidade', 'callcenter_copiloto',
                          'callcenter_agente_ia'));

INSERT INTO financeiro_cost_alerts (scope, threshold_usd, enabled) VALUES ('callcenter_agente_ia', 0, FALSE);
