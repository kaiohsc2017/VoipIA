package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDateTime;

/** ChatReportRow — uma linha do relatório analítico de chat (Fase 9c). {@code transcriptPath} é
 * o caminho já exportado por {@code CcChatSession} (Fase 7a/11) — sem análise de IA (o canal de
 * chat não passa pelo pipeline de Insights, diferente da gravação de voz da Fase 8). */
public record ChatReportRow(
        Long sessionId,
        LocalDateTime startedAt,
        LocalDateTime claimedAt,
        LocalDateTime closedAt,
        String customerRef,
        String customerName,
        String queueName,
        String agentName,
        String dispositionName,
        String transcriptPath) {}
