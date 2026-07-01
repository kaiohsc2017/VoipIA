package com.asteriskia.domain.call;

import java.time.LocalDateTime;

/**
 * CallRecordFilter — combinação de filtros opcionais para listagem de chamadas.
 * Qualquer campo nulo/em branco é ignorado na query.
 */
public record CallRecordFilter(
        String callerNumber,
        String clientName,
        String ramal,
        String callType,
        String jiraIssueKey,
        String transcriptionText,
        String priority,
        LocalDateTime dateFrom,
        LocalDateTime dateTo
) {}
