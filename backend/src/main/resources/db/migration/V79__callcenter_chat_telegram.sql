-- Fase 7e do plano de fechamento 5/7/9 do Call Center — canal de chat via Telegram (long polling,
-- D1/D2, sem webhook público). O motor de fluxo/chat já existente (Fase 7a/24) é reaproveitado sem
-- mudança nenhuma no domínio de sessão/mensagem/fluxo — só ganha um jeito novo de "entrar" (Telegram
-- ao invés do widget webchat).

-- Token do bot NUNCA em texto puro nesta tabela — é uma referência (chave) para uma entrada do
-- .env gerenciada por SettingsService/EnvFileStore, mesmo padrão de outros segredos do projeto
-- (sufixo _CREDENTIAL/_TOKEN mascarado em GET /settings, valor real só acessível via leitura raw
-- no backend). Ex.: "CALLCENTER_TELEGRAM_BOT_TOKEN".
ALTER TABLE cc_chat_channels
    ADD COLUMN telegram_bot_token_ref VARCHAR(100);

-- Correlação do chat_id do Telegram com a sessão aberta (D2). Índice único parcial garante que
-- nunca existam duas sessões simultâneas abertas para o mesmo chat_id no mesmo canal.
ALTER TABLE cc_chat_sessions
    ADD COLUMN external_ref VARCHAR(120);

CREATE UNIQUE INDEX uq_cc_chat_sessions_channel_external_open
    ON cc_chat_sessions (channel_id, external_ref)
    WHERE closed_at IS NULL;

-- Retomada do polling após restart, sem reprocessar updates antigos — uma linha por canal
-- Telegram (PK = channel_id, não FK formal para permitir excluir/recriar canal sem travar o
-- expurgo do estado de polling; a referência é mantida pela aplicação).
CREATE TABLE cc_telegram_poll_state (
    channel_id      BIGINT PRIMARY KEY,
    last_update_id  BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
