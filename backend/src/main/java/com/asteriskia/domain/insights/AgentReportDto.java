package com.asteriskia.domain.insights;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** AgentReportSummaryDto — linha da listagem de relatórios (Fase 2 QM, V39). requestedBy
 * só é relevante pra ADMIN (não-ADMIN só vê os próprios, coluna redundante pra ele). */
public record AgentReportDto(
        Long id,
        String agentName,
        LocalDate dateFrom,
        LocalDate dateTo,
        String requestedBy,
        OffsetDateTime requestedAt,
        String status,
        String errorMsg,
        AgentReportContent content,
        Long previousReportId,
        AgentReportEvolution evolution,
        OffsetDateTime completedAt
) {}
