package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import org.springframework.stereotype.Service;

/**
 * ChatBlendingService — resolve o limite de chats simultâneos de um agente (Fase 7c), decidido
 * com o usuário: se o agente tiver um valor próprio maior que zero, ele sempre prevalece sobre o
 * da fila; se o agente estiver nulo ou zerado, vale o limite configurado na fila; se os dois
 * forem nulos, não há limite (regra desligada, comportamento igual ao de antes desta fatia).
 *
 * <p>"Voz sempre ganha" não é responsabilidade deste serviço — é garantido estruturalmente por
 * {@link CcChatService#claim} exigir {@code AgentState.DISPONIVEL}: um agente em chamada nunca
 * está disponível, então nunca chega a ser avaliado por este limite.
 */
@Service
public class ChatBlendingService {

    /** Nulo = sem limite (agente pode assumir quantos chats quiser). */
    public Integer resolveLimit(CcAgent agent, CcQueue queue) {
        Integer agentLimit = agent.getMaxConcurrentChats();
        if (agentLimit != null && agentLimit > 0) {
            return agentLimit;
        }
        return queue.getMaxConcurrentChats();
    }
}
