package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;

/**
 * AgentGamificationRow — uma linha do ranking de gamificação (Fase 27). {@code position} é nulo
 * para agentes abaixo do volume mínimo — eles aparecem em {@link GamificationReport#belowMinimum()},
 * nunca ranqueados junto dos elegíveis.
 */
public record AgentGamificationRow(
        Integer position,
        Long agentId,
        String agentName,
        int totalAtendidas,
        int totalRealizadas,
        BigDecimal npsMedio) {
}
