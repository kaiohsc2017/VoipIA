-- V68 — Fase 24 do plano Call Center Parte III (canais de chat + flow builder de chat).
--
-- Decisão D8 já registrada no plano (modulo-callcenter-omnicanal.plan.md): a aplicação roda
-- dentro da rede corporativa, nunca vai à internet aberta — "widget público" (Fase 7b) passa a
-- significar "widget interno", servido só para origens da intranet. Esta migration não renomeia
-- a rota (/api/v1/callcenter/chat/public/**, mantida por compatibilidade), só documenta.
COMMENT ON TABLE cc_chat_channels IS
    'Canal de origem de uma sessão de chat — "público"/"public" no código é vocabulário legado '
    'da Fase 7b; a aplicação nunca é exposta à internet aberta (D8), roda dentro da rede '
    'corporativa. Fase 24: cada canal ganha fila padrão (substitui a variável de ambiente única '
    'CALLCENTER_CHAT_PUBLIC_QUEUE_ID) e, opcionalmente, um fluxo de bot do Flow Builder.';

ALTER TABLE cc_chat_channels
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'webchat',
    ADD COLUMN default_queue_id BIGINT REFERENCES cc_queues(id),
    ADD COLUMN bot_flow_id BIGINT REFERENCES cc_flows(id),
    ADD COLUMN greeting_message TEXT,
    ADD COLUMN away_message TEXT;

COMMENT ON COLUMN cc_chat_channels.default_queue_id IS
    'Fila para onde uma sessão sem bot (ou após o bot transferir) é roteada — substitui '
    'app.callcenter.chat.public-queue-id (Fase 7b). Sem fila configurada, o canal responde 503, '
    'nunca 500 (mesmo contrato já usado pela variável de ambiente que este campo substitui).';
COMMENT ON COLUMN cc_chat_channels.bot_flow_id IS
    'Fluxo do Flow Builder (canal chat) que atende a sessão antes de chegar a um agente humano — '
    'nulo = sessão vai direto para a fila (comportamento da Fase 7a/7b, preservado).';

-- "bot": sessão sendo atendida pelo motor de fluxo (ChatChannelDriver), antes de chegar à fila
-- de um agente humano — nunca visível em listWaiting()/claim() (só filas ganham chamador humano).
ALTER TABLE cc_chat_sessions DROP CONSTRAINT IF EXISTS cc_chat_sessions_status_check;
ALTER TABLE cc_chat_sessions
    ADD CONSTRAINT cc_chat_sessions_status_check CHECK (status IN ('bot', 'waiting', 'active', 'closed'));

-- "bot": mensagem automática enviada pelo motor de fluxo (nó tocar_audio/menu_opcoes em canal
-- chat) — nunca confiada do chamador, só CcChatService.postBotMessage grava com este senderType.
ALTER TABLE cc_chat_messages DROP CONSTRAINT IF EXISTS cc_chat_messages_sender_type_check;
ALTER TABLE cc_chat_messages
    ADD CONSTRAINT cc_chat_messages_sender_type_check CHECK (sender_type IN ('customer', 'agent', 'system', 'bot'));
