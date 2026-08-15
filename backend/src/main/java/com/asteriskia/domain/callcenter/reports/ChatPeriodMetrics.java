package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;

/**
 * ChatPeriodMetrics — um período (dia/semana/mês/ano) agregado de chat de uma fila (Fase 9c.2).
 */
public record ChatPeriodMetrics(
        Long queueId,
        String queueName,
        String periodLabel,
        int received,
        int claimed,
        int closed,
        int botContained,
        int botEscalated,
        BigDecimal botContainmentRatePct,
        BigDecimal avgFrtSeconds,
        BigDecimal avgResponseSeconds,
        BigDecimal avgConcurrentChats) {
}
