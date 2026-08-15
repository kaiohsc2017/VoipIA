-- V82 — Sub-fase 9c.6 do módulo Call Center (Fase 9, Relatórios analíticos): agendamento de
-- exportação periódica do relatório de chamada/chat (Fase 9c.5), entregue por Telegram ou e-mail
-- (CFG-email). Depende da CFG-email (aba SMTP em Configuração) já entregue para o canal e-mail —
-- o canal Telegram não depende de nada novo (reusa CALLCENTER_TELEGRAM_BOT_TOKEN, Fase 7e).

CREATE TABLE cc_report_schedules (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(150) NOT NULL,
    report_type       VARCHAR(20) NOT NULL,   -- CALLS_EXCEL | CALLS_PDF | CHATS_EXCEL | CHATS_PDF
    queue_id          BIGINT REFERENCES cc_queues(id),
    agent_id          BIGINT REFERENCES cc_agents(id),
    period_days       INT NOT NULL DEFAULT 7,
    frequency         VARCHAR(10) NOT NULL,   -- DAILY | WEEKLY | MONTHLY
    day_of_week       INT,                    -- 1 (segunda) a 7 (domingo) — obrigatório se WEEKLY
    day_of_month      INT,                    -- 1 a 28 — obrigatório se MONTHLY
    hour_of_day       INT NOT NULL DEFAULT 8,
    channel           VARCHAR(10) NOT NULL,   -- telegram | email
    recipient         VARCHAR(255) NOT NULL,  -- chat_id do Telegram, ou endereço de e-mail
    active            BOOLEAN NOT NULL DEFAULT true,
    created_by        VARCHAR(100) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_run_at       TIMESTAMPTZ,
    last_run_status   VARCHAR(20)             -- OK | FAILED
);

COMMENT ON TABLE cc_report_schedules IS 'Agendamento de exportação periódica do relatório de chamada/chat (Fase 9c.6)';
COMMENT ON COLUMN cc_report_schedules.period_days IS 'Janela do relatório gerado a cada execução: últimos N dias a partir da data de execução (não do agendamento)';
COMMENT ON COLUMN cc_report_schedules.recipient IS 'Escopo (fila/agente/período) e destinatário são congelados na criação — reavaliados só na execução (mesma disciplina de fail-closed já aplicada em cc_quality_reports, achado HIGH real da Fase 26)';
