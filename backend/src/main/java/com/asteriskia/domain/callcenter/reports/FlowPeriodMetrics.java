package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;

/**
 * FlowPeriodMetrics — um período (dia/semana/mês/ano) agregado de um fluxo visual (Fase 9c.1).
 */
public record FlowPeriodMetrics(
        Long flowId,
        String flowName,
        String periodLabel,
        int executions,
        int completed,
        int transferredQueue,
        int transferredExtension,
        int abandoned,
        int errored,
        BigDecimal abandonRatePct,
        BigDecimal avgDurationSeconds) {
}
