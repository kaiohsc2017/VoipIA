-- V51 — Supervisão em tempo real (Fase 6). cc_supervision_actions audita toda ação do supervisor
-- sobre um agente (escuta/sussurro/interceptação são literalmente escuta de conversa — exige
-- rastro, LGPD art. 37); cc_queue_alert_config guarda o limiar de SLA por fila (espera máxima e/ou
-- nível de serviço mínimo) para o alerta via Telegram, mesmo padrão de granularidade diária do
-- alerta de disco (V49) — uma fila pode ficar acima do limite por vários dias seguidos.

CREATE TABLE cc_supervision_actions (
    id                  BIGSERIAL PRIMARY KEY,
    supervisor_user_id  INTEGER NOT NULL REFERENCES app_users(id),
    agent_id            BIGINT NOT NULL REFERENCES cc_agents(id),
    action_type         VARCHAR(20) NOT NULL,
    started_at          TIMESTAMP NOT NULL DEFAULT now(),
    ended_at            TIMESTAMP
);
CREATE INDEX idx_cc_supervision_actions_agent_id ON cc_supervision_actions(agent_id);

CREATE TABLE cc_queue_alert_config (
    queue_id                   BIGINT PRIMARY KEY REFERENCES cc_queues(id) ON DELETE CASCADE,
    max_waiting_count          INTEGER,
    min_service_level_percent  INTEGER,
    enabled                    BOOLEAN NOT NULL DEFAULT false,
    last_notified_date         DATE,
    updated_by                 VARCHAR(100),
    updated_at                 TIMESTAMP NOT NULL DEFAULT now()
);
