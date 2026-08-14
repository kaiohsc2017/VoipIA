-- Fase 7c do plano de fechamento 5/7/9 do Call Center — blending de chat: limite de chats
-- simultâneos por agente, com sobreposição fila->agente (D5, confirmado com o usuário).
--
-- Regra de resolução (ChatBlendingService.resolveLimit, decidida com o usuário):
--   agente.maxConcurrentChats NULO ou ZERO -> vale o limite da FILA (cc_queues.max_concurrent_chats).
--   agente.maxConcurrentChats > 0          -> o valor do AGENTE sempre prevalece sobre o da fila.
--   Os dois nulos -> sem limite (regra desligada, comportamento igual ao de antes desta fatia).
-- "Voz sempre ganha" já é garantido estruturalmente por CcChatService.claim() exigir
-- AgentState.DISPONIVEL — um agente em chamada nunca está DISPONIVEL, então nunca chega a ser
-- avaliado por este limite.

ALTER TABLE cc_queues
    ADD COLUMN max_concurrent_chats INTEGER;

ALTER TABLE cc_queues
    ADD CONSTRAINT chk_cc_queues_max_concurrent_chats CHECK (max_concurrent_chats IS NULL OR max_concurrent_chats >= 1);

ALTER TABLE cc_agents
    ADD COLUMN max_concurrent_chats INTEGER;

ALTER TABLE cc_agents
    ADD CONSTRAINT chk_cc_agents_max_concurrent_chats CHECK (max_concurrent_chats IS NULL OR max_concurrent_chats >= 0);
