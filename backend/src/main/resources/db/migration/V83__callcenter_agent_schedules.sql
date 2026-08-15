-- V83 — Sub-fase 9c.7 do módulo Call Center (Fase 9, Relatórios analíticos): escala do agente
-- (turno esperado por dia da semana) e aderência à escala. Depende da Fase 5e (horário de
-- funcionamento/feriados, já entregue) só no sentido de reusar o mesmo conceito de janela de
-- horário — cc_business_hours (V74) é da OPERAÇÃO/fila, cc_agent_schedules é do AGENTE
-- individual, tabelas deliberadamente separadas (turnos de agente não têm por que coincidir com
-- o horário de atendimento da fila: folga, meio período, etc.).

CREATE TABLE cc_agent_schedules (
    id           BIGSERIAL PRIMARY KEY,
    agent_id     BIGINT NOT NULL REFERENCES cc_agents(id),
    day_of_week  INT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7), -- 1=segunda .. 7=domingo (ISO-8601)
    start_time   TIME NOT NULL,
    end_time     TIME NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_agent_schedules IS 'Turno esperado de um agente por dia da semana (Fase 9c.7) — base do cálculo de aderência à escala';
COMMENT ON COLUMN cc_agent_schedules.day_of_week IS 'ISO-8601: 1=segunda, 7=domingo (mesmo padrão já usado em cc_business_hours, V74)';

CREATE INDEX idx_cc_agent_schedules_agent_day ON cc_agent_schedules(agent_id, day_of_week) WHERE active;
