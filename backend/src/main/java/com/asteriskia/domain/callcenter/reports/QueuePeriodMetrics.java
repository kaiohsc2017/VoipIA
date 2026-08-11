package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;

/**
 * QueuePeriodMetrics — um período (dia/semana/mês/ano) agregado de uma fila, já pronto pra
 * exibição (taxa de abandono calculada aqui, não no frontend, pra não divergir entre telas).
 */
public record QueuePeriodMetrics(
        Long queueId,
        String queueName,
        String periodLabel,
        int received,
        int answered,
        int abandoned,
        BigDecimal abandonRatePct,
        BigDecimal avgWaitSeconds,
        BigDecimal avgTalkSeconds,
        BigDecimal serviceLevelPct) {
}
