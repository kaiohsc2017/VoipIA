package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/**
 * InsightsFilter — combinação de filtros opcionais de busca da tela Insights.
 * Qualquer campo nulo/em branco é ignorado na query.
 */
public record InsightsFilter(
        LocalDateTime dateFrom,
        LocalDateTime dateTo,
        String text,
        String phrase,
        String toneCliente,
        String toneAtendente,
        String categoria
) {}
