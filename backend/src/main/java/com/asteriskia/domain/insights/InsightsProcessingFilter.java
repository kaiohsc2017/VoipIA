package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/** InsightsProcessingFilter — filtros opcionais da aba "Processamento". Datas filtram por
 * ingestedAt (quando o arquivo foi descoberto) — é o campo que sempre existe, mesmo para
 * chamadas ainda pendentes/em erro (processedAt pode ser nulo nesses casos). */
public record InsightsProcessingFilter(
        String status,
        LocalDateTime dateFrom,
        LocalDateTime dateTo,
        String fileName
) {}
