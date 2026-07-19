package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/**
 * InsightsFilter — combinação de filtros opcionais de busca da tela Insights.
 * Qualquer campo nulo/em branco é ignorado na query.
 */
public record InsightsFilter(
        Long id,
        LocalDateTime dateFrom,
        LocalDateTime dateTo,
        String text,
        String phrase,
        String toneCliente,
        String toneAtendente,
        String categoria,
        String criticidade,
        String findingType,
        String agentName,
        String direction,
        String skill,
        Integer durationMin,
        Integer durationMax
) {}
