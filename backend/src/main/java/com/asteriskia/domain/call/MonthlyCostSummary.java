package com.asteriskia.domain.call;

import java.math.BigDecimal;

/** Custo total de IA agregado por mês (yyyy-MM) — usado no gráfico do Dashboard de Custos. */
public record MonthlyCostSummary(
        String month,
        BigDecimal sttCostUsd,
        BigDecimal llmCostUsd,
        BigDecimal ttsCostUsd,
        BigDecimal totalCostUsd,
        long callCount) {}
