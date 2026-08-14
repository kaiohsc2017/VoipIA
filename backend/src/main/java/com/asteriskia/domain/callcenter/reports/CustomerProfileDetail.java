package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * CustomerProfileDetail — histórico completo de um cliente (Fase 27, "Perfil do cliente"):
 * top assuntos (tabulação de voz + categoria de insight, quando houver), chamadas e chats no
 * período, mais recentes primeiro.
 */
public record CustomerProfileDetail(
        String normalizedId,
        String displayContact,
        int totalChamadas,
        int totalChats,
        BigDecimal npsMedio,
        List<SubjectCount> topAssuntos,
        List<InteractionSummary> chamadas,
        List<ChatSummary> chats) {

    public record SubjectCount(String assunto, long total) {}

    public record InteractionSummary(
            Long interactionId,
            LocalDateTime queuedAt,
            String queueName,
            String agentName,
            BigDecimal npsScore,
            String dispositionLabel,
            String categoriaAssunto) {}

    public record ChatSummary(
            Long sessionId,
            LocalDateTime startedAt,
            String queueName,
            String agentName,
            String dispositionLabel) {}
}
