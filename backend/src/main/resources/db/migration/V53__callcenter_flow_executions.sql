-- V53 — Motor de execução do Flow Builder (Fase 5, sub-fase 5b). cc_flow_executions guarda uma
-- linha por chamada real que passou pelo app Stasis "callcenter" (extensão 6XXX): qual fluxo e,
-- crucialmente, qual VERSÃO publicada rodou (a versão é fixada no início e nunca muda, mesmo que
-- uma nova seja publicada durante a chamada — é a mesma garantia de imutabilidade da V52).
-- cc_flow_execution_steps é o traço nó a nó — permite ver exatamente onde o cliente abandonou.
-- interaction_id é referência fraca (sem FK) para cc_interactions: subpacote diferente
-- (domain.callcenter.interaction), mesmo padrão de desacoplamento já usado entre os subpacotes do
-- módulo Call Center nesta fase.

CREATE TABLE cc_flow_executions (
    id                  BIGSERIAL PRIMARY KEY,
    flow_id             BIGINT NOT NULL REFERENCES cc_flows(id),
    flow_version_id     BIGINT NOT NULL REFERENCES cc_flow_versions(id),
    interaction_id      BIGINT,
    channel_id          VARCHAR(80) NOT NULL,
    channel_unique_id   VARCHAR(80),
    started_at          TIMESTAMP NOT NULL DEFAULT now(),
    ended_at            TIMESTAMP,
    outcome             VARCHAR(30),
    last_node_id        VARCHAR(64)
);
CREATE INDEX idx_cc_flow_executions_flow_started ON cc_flow_executions(flow_id, started_at);
CREATE INDEX idx_cc_flow_executions_channel_id ON cc_flow_executions(channel_id);

CREATE TABLE cc_flow_execution_steps (
    id              BIGSERIAL PRIMARY KEY,
    execution_id    BIGINT NOT NULL REFERENCES cc_flow_executions(id) ON DELETE CASCADE,
    node_id         VARCHAR(64) NOT NULL,
    node_type       VARCHAR(40) NOT NULL,
    entered_at      TIMESTAMP NOT NULL DEFAULT now(),
    exited_at       TIMESTAMP,
    taken_edge      VARCHAR(64),
    -- Nunca dado sensível/entrada livre do usuário — só metadados como o dígito de um menu
    -- (FlowExecutionTraceService/handlers são responsáveis por nunca gravar aqui o valor de um
    -- nó "coletar entrada" marcado como sensível — essa flag chega na sub-fase 5d).
    detail          TEXT
);
CREATE INDEX idx_cc_flow_execution_steps_execution_entered ON cc_flow_execution_steps(execution_id, entered_at);
