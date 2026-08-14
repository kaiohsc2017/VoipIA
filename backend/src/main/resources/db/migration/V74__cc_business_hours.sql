-- Fase 5e.1 do plano de fechamento 5/7/9 do Call Center — horário de funcionamento/feriados do
-- Flow Builder (nó "horario_funcionamento", desbloqueado por esta migration).
-- `cc_holidays` já existe (V70, Fase 26) — reusada aqui, não duplicada: só ganha `calendar_id`
-- nullable, aditivo (feriado sem calendário = feriado GLOBAL, fecha todos os calendários).

-- Calendário nomeado (um por fuso horário/regra de horário — ex.: "Matriz", "Suporte 24x7").
CREATE TABLE cc_business_hours (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    timezone    VARCHAR(60) NOT NULL DEFAULT 'America/Sao_Paulo',
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Faixas de horário dentro de um dia da semana — N por calendário/dia, cobrindo turno partido
-- (ex.: 08:00-12:00 e 13:00-18:00 no mesmo dia). day_of_week segue java.time.DayOfWeek.getValue()
-- (1=segunda .. 7=domingo).
CREATE TABLE cc_business_hours_slots (
    id           BIGSERIAL PRIMARY KEY,
    calendar_id  BIGINT NOT NULL REFERENCES cc_business_hours(id) ON DELETE CASCADE,
    day_of_week  SMALLINT NOT NULL,
    start_time   TIME NOT NULL,
    end_time     TIME NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cc_business_hours_slots_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_cc_business_hours_slots_range CHECK (end_time > start_time)
);
CREATE INDEX idx_cc_business_hours_slots_calendar_day ON cc_business_hours_slots(calendar_id, day_of_week);

-- Feriado sem calendário (calendar_id NULL) é GLOBAL — fecha todos os calendários. Feriado
-- vinculado a um calendário específico fecha só aquele calendário. A tela de feriados existente
-- (Fase 26, CcQualityReportController) continua só criando feriados globais nesta fatia — atribuir
-- um feriado a um calendário específico fica para quando a UI de calendário precisar disso.
ALTER TABLE cc_holidays ADD COLUMN calendar_id BIGINT REFERENCES cc_business_hours(id) ON DELETE CASCADE;
CREATE INDEX idx_cc_holidays_calendar ON cc_holidays(calendar_id);
