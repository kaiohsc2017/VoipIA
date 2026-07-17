package com.asteriskia.domain.insights;

import java.util.Map;

/** InsightsDashboardSummary — métricas agregadas para o dashboard de tendências. */
public record InsightsDashboardSummary(
        long totalChamadas,
        Map<String, Long> porCriticidade,
        Map<String, Long> porCategoria,
        Map<String, Long> achadosPorTipo
) {}
