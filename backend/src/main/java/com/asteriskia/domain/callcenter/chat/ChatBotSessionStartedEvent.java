package com.asteriskia.domain.callcenter.chat;

/**
 * ChatBotSessionStartedEvent — publicado por {@link CcChatService#startSession} quando o canal
 * tem um fluxo de bot associado (Fase 24). Ouvido por
 * {@code com.asteriskia.domain.callcenter.flow.chat.ChatFlowLauncherService}, que instancia o
 * driver e dispara a execução — evento em vez de chamada direta para não criar uma dependência
 * circular entre o pacote {@code chat} (que precisaria conhecer o motor de fluxo) e o pacote
 * {@code flow} (cujo driver de chat já depende de {@link CcChatService}).
 */
public record ChatBotSessionStartedEvent(Long sessionId, Long flowId) {}
