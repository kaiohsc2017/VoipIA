-- V71 — Particionamento de cc_interaction_events e cc_chat_messages (Fase 10, parte 2 do plano
-- do Call Center). Decisão do usuário (2026-08-14, ver
-- .claude/plans/callcenter-fase10-seguranca-endurecimento.plan.md §10): particionar agora, mesmo
-- com volume real ~zero nesta VPS (nenhuma chamada de voz nem chat real passou pelo Call Center
-- ainda) — confirmado por SELECT count(*) = 0 nas duas tabelas antes desta migration, o que
-- elimina o risco normalmente associado a essa conversão (nenhum dado a migrar/perder).
--
-- Estratégia: RANGE mensal por occurred_at/created_at, cobrindo 2025-01 a 2027-12 (margem de anos
-- para não precisar de manutenção imediata) + uma partição DEFAULT em cada tabela, para nunca
-- falhar um INSERT por falta de partição (linha fora do range cai lá, visível/auditável, nunca
-- perdida). Nenhum código Java precisa mudar: `ddl-auto=none` (schema 100% gerenciado pelo
-- Flyway) e as entidades (`CcInteractionEvent`/`CcChatMessage`) já usam só a coluna `id` via
-- `@GeneratedValue(IDENTITY)` — o Postgres exige que toda chave primária/única de uma tabela
-- particionada inclua a coluna de particionamento, então o PK virou composto (id, occorred_at/
-- created_at); `id` continua globalmente único (mesma sequência BIGSERIAL), então toda consulta
-- por id (findById, JPQL) continua funcionando sem alteração.
--
-- Gap aceito, documentado (não implementado nesta migration — fora do pedido do usuário):
-- não há job agendado para criar novas partições além de 2027-12. Antes dessa data, alguém
-- precisa rodar uma migration nova (ou um scheduler dedicado, mesmo padrão de
-- AiModelPricingSyncScheduler) estendendo o range — se ninguém fizer isso a tempo, os inserts
-- simplesmente caem na partição DEFAULT (sem falha, só sem o benefício de pruning).

-- ============================================================================================
-- cc_interaction_events
-- ============================================================================================

DROP TABLE cc_interaction_events;

CREATE TABLE cc_interaction_events (
    id             BIGSERIAL,
    interaction_id BIGINT NOT NULL REFERENCES cc_interactions(id) ON DELETE CASCADE,
    event_type     VARCHAR(40) NOT NULL,
    details        TEXT,
    occurred_at    TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE cc_interaction_events IS 'Traço bruto de eventos AMI por interação (Fase 4) — particionada por mês (occurred_at) desde a Fase 10/parte 2';

CREATE INDEX idx_cc_interaction_events_interaction_id ON cc_interaction_events(interaction_id);

DO $$
DECLARE
    d date := DATE '2025-01-01';
    d_end date := DATE '2028-01-01';
BEGIN
    WHILE d < d_end LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF cc_interaction_events FOR VALUES FROM (%L) TO (%L)',
            'cc_interaction_events_' || to_char(d, 'YYYY_MM'),
            d,
            (d + interval '1 month')::date
        );
        d := (d + interval '1 month')::date;
    END LOOP;
END $$;

CREATE TABLE cc_interaction_events_default PARTITION OF cc_interaction_events DEFAULT;

-- ============================================================================================
-- cc_chat_messages
-- ============================================================================================

DROP TABLE cc_chat_messages;

CREATE TABLE cc_chat_messages (
    id          BIGSERIAL,
    session_id  BIGINT NOT NULL REFERENCES cc_chat_sessions(id) ON DELETE CASCADE,
    sender_type VARCHAR(10) NOT NULL CHECK (sender_type IN ('customer', 'agent', 'system')),
    sender_name VARCHAR(150),
    body        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

COMMENT ON TABLE cc_chat_messages IS 'Mensagens de uma sessão de chat, em ordem cronológica (Fase 7a) — particionada por mês (created_at) desde a Fase 10/parte 2';

CREATE INDEX idx_cc_chat_messages_session ON cc_chat_messages(session_id, created_at);

DO $$
DECLARE
    d date := DATE '2025-01-01';
    d_end date := DATE '2028-01-01';
BEGIN
    WHILE d < d_end LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF cc_chat_messages FOR VALUES FROM (%L) TO (%L)',
            'cc_chat_messages_' || to_char(d, 'YYYY_MM'),
            d,
            (d + interval '1 month')::date
        );
        d := (d + interval '1 month')::date;
    END LOOP;
END $$;

CREATE TABLE cc_chat_messages_default PARTITION OF cc_chat_messages DEFAULT;
