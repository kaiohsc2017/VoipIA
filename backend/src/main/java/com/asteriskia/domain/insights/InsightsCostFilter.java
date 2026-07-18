package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/** InsightsCostFilter — filtros opcionais da aba "Custos IA" de Insights. Mirror de
 * CallRecordFilter, reduzido aos campos relevantes (sem URA — Insights não tem). */
public record InsightsCostFilter(
        LocalDateTime dateFrom,
        LocalDateTime dateTo,
        String agentName
) {}
