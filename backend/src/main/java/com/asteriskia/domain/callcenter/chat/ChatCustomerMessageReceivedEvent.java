package com.asteriskia.domain.callcenter.chat;

/**
 * ChatCustomerMessageReceivedEvent — publicado por {@link CcChatService#postMessage} a cada
 * mensagem de cliente, independente de haver ou não uma execução de bot esperando (Fase 24). O
 * listener em {@code flow.chat.ChatFlowLauncherService} ignora silenciosamente quando não há
 * driver registrado para a sessão.
 */
public record ChatCustomerMessageReceivedEvent(Long sessionId, String text) {}
