package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;

/**
 * AgentPeriodMetrics — um período (dia/semana/mês/ano) agregado de um agente, já pronto pra
 * exibição (mesmo padrão de {@code QueuePeriodMetrics}, sub-fase 9a).
 */
public record AgentPeriodMetrics(
        Long agentId,
        String agentName,
        String periodLabel,
        int answered,
        BigDecimal avgTalkSeconds,
        long occupiedSeconds,
        long availableSeconds,
        long pausedSeconds,
        long offlineSeconds,
        BigDecimal occupancyPct) {
}
