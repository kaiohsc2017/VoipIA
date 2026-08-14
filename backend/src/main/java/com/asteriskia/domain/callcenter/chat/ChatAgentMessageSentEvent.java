package com.asteriskia.domain.callcenter.chat;

/**
 * ChatAgentMessageSentEvent — publicado por {@link CcChatService#postMessage} (mensagem de agente
 * ou sistema) e {@link CcChatService#postBotMessage} (mensagem do motor de fluxo) sempre que a
 * sessão pertence a um canal com entrega externa (Fase 7e — Telegram). Webchat não tem nenhum
 * listener para este evento (entrega é só polling do próprio frontend) — publicar sem custo, mesmo
 * padrão de {@link ChatCustomerMessageReceivedEvent} (ignorado silenciosamente quando não há
 * listener interessado).
 */
public record ChatAgentMessageSentEvent(Long sessionId, String body) {}
