package com.asteriskia.domain.callcenter.reports;

import java.util.List;

/**
 * GamificationReport — ranking de agentes por NPS médio no período (Fase 27). {@code ranking}
 * só contém agentes com {@code totalAtendidas >= minCalls} — "exibir volume mínimo" é o próprio
 * plano dizendo que um agente com poucas chamadas e NPS 10 não é o melhor da operação.
 * {@code belowMinimum} lista os demais, sem posição, para transparência (nada é escondido, só
 * não ranqueado).
 */
public record GamificationReport(
        int minCalls,
        List<AgentGamificationRow> ranking,
        List<AgentGamificationRow> belowMinimum) {
}
