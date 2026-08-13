-- Fase 21 do plano omnicanal Parte III — pesquisa de satisfação (NPS) pós-atendimento.
-- 4 modos, escolhidos na criação de cada pesquisa (D17): DTMF_SIMPLES, DTMF_MULTI, FALADA_IA,
-- DTMF_COMENTARIO. Ativação por fila (cc_queues.survey_id) sobreposta pelo interruptor global
-- já existente (cc_settings.nps_enabled, Fase 19).

CREATE TABLE cc_surveys (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(150) NOT NULL,
    mode              VARCHAR(20) NOT NULL,
    scale_max         INT NOT NULL DEFAULT 10,
    active            BOOLEAN NOT NULL DEFAULT true,
    business_unit_id  INTEGER REFERENCES business_units(id),
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON COLUMN cc_surveys.mode IS 'DTMF_SIMPLES|DTMF_MULTI|FALADA_IA|DTMF_COMENTARIO — governa como cc_survey_questions é coletado (Fase 21, D17)';
COMMENT ON COLUMN cc_surveys.scale_max IS 'Escala válida da nota por dígito: 10 (0-9 mais * = 10) ou 5 (0-5) — precisa ficar explícita, escalas diferentes não são comparáveis num histórico único';

CREATE TABLE cc_survey_questions (
    id            BIGSERIAL PRIMARY KEY,
    survey_id     BIGINT NOT NULL REFERENCES cc_surveys(id) ON DELETE CASCADE,
    order_index   INT NOT NULL,
    text          VARCHAR(500) NOT NULL,
    audio_path    VARCHAR(255),
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_cc_survey_questions_survey_order ON cc_survey_questions(survey_id, order_index);
COMMENT ON COLUMN cc_survey_questions.audio_path IS 'Áudio da pergunta (TTS pré-gravado ou upload) — playMessage toca este arquivo; texto é o fallback/legenda';

CREATE TABLE cc_survey_responses (
    id                BIGSERIAL PRIMARY KEY,
    interaction_id    BIGINT NOT NULL REFERENCES cc_interactions(id) ON DELETE CASCADE,
    question_id       BIGINT NOT NULL REFERENCES cc_survey_questions(id),
    value             INT,
    transcript        TEXT,
    audio_path        VARCHAR(255),
    ai_cost_usd       NUMERIC(10, 6),
    skipped_reason    VARCHAR(60),
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_cc_survey_responses_interaction_id ON cc_survey_responses(interaction_id);
COMMENT ON COLUMN cc_survey_responses.value IS 'Nota por dígito (DTMF_SIMPLES/DTMF_MULTI/DTMF_COMENTARIO) ou classificação 0-10 derivada da IA (FALADA_IA) — nula enquanto a transcrição assíncrona não roda';
COMMENT ON COLUMN cc_survey_responses.transcript IS 'Texto transcrito do áudio (FALADA_IA — automático assíncrono; DTMF_COMENTARIO — só sob demanda, D21, nunca automático)';
COMMENT ON COLUMN cc_survey_responses.ai_cost_usd IS 'Custo real da transcrição+classificação desta resposta — nulo se a pergunta nunca gerou custo de IA (todo DTMF) ou ainda não foi processada';
COMMENT ON COLUMN cc_survey_responses.skipped_reason IS 'Preenchido quando a pergunta não foi coletada (timeout, desistência do cliente) — nunca junto com value/transcript';

ALTER TABLE cc_queues ADD COLUMN survey_id BIGINT REFERENCES cc_surveys(id);
ALTER TABLE cc_queues ADD COLUMN nps_alert_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE cc_queues ADD COLUMN nps_alert_threshold INT;
COMMENT ON COLUMN cc_queues.survey_id IS 'Pesquisa desta fila — nulo = sem pesquisa. O interruptor global de cc_settings (Fase 19) sobrepõe mesmo com survey_id preenchido';
COMMENT ON COLUMN cc_queues.nps_alert_threshold IS 'Nota (na escala de cc_surveys.scale_max da pesquisa desta fila) igual ou abaixo da qual dispara alerta Telegram imediato';

ALTER TABLE cc_interactions ADD COLUMN nps_score NUMERIC(4, 1);
COMMENT ON COLUMN cc_interactions.nps_score IS 'Nota desnormalizada da pesquisa desta interação (média das perguntas com nota) — evita join pesado em relatório/agregado; nula se não pesquisada ou pesquisa sem nenhuma resposta com nota ainda';

ALTER TABLE cc_agg_queue_daily ADD COLUMN avg_nps_score NUMERIC(4, 1);
ALTER TABLE cc_agg_agent_daily ADD COLUMN avg_nps_score NUMERIC(4, 1);
COMMENT ON COLUMN cc_agg_queue_daily.avg_nps_score IS 'Média de cc_interactions.nps_score das interações desse dia com nota — nula se nenhuma interação do dia foi pesquisada com nota';
COMMENT ON COLUMN cc_agg_agent_daily.avg_nps_score IS 'Mesma métrica de cc_agg_queue_daily.avg_nps_score, por agente';

-- Nova frente de custo de IA no Financeiro (§21.5) — mesmo padrão de V42/V54. Só existe gasto
-- real quando o modo FALADA_IA é usado (transcrição+classificação assíncrona, nunca durante a
-- chamada) — todo DTMF é zero custo, a tela do Financeiro precisa deixar isso explícito.
ALTER TABLE financeiro_cost_alerts DROP CONSTRAINT chk_financeiro_cost_alerts_scope;
ALTER TABLE financeiro_cost_alerts
    ADD CONSTRAINT chk_financeiro_cost_alerts_scope
        CHECK (scope IN ('ura', 'insights', 'envios', 'callcenter', 'callcenter_nps'));

INSERT INTO financeiro_cost_alerts (scope, threshold_usd, enabled) VALUES ('callcenter_nps', 0, FALSE);
