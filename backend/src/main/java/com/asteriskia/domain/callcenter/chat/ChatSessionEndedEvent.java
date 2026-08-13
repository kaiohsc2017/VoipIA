package com.asteriskia.domain.callcenter.chat;

/**
 * ChatSessionEndedEvent — publicado por {@link CcChatService#close} quando uma sessão é encerrada
 * por uma via externa ao bot (ex.: ADMIN força o fechamento de uma sessão "bot" travada esperando
 * resposta). Ouvido por {@code com.asteriskia.domain.callcenter.flow.chat.ChatFlowLauncherService},
 * que destrava a thread do fluxo em vez de deixá-la rodar até o timeout do nó — sem custo se não
 * houver bot em execução para esta sessão (mesmo padrão de {@link ChatCustomerMessageReceivedEvent}).
 */
public record ChatSessionEndedEvent(Long sessionId) {}
