package com.asteriskia.domain.insights;

import java.math.BigDecimal;

/** Custo total de IA agregado por mês (yyyy-MM) do módulo Insights — usado no gráfico do
 * Dashboard de Custos. Mirror de MonthlyCostSummary (domain/call/), sem campo TTS. */
public record InsightMonthlyCostSummary(
        String month,
        BigDecimal sttCostUsd,
        BigDecimal llmCostUsd,
        BigDecimal totalCostUsd,
        long callCount) {}
