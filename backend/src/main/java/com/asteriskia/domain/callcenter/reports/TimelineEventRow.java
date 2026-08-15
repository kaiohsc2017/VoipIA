package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * TimelineEventRow — um evento (voz ou chat) na timeline omnicanal de um contato (Fase 9c.3).
 */
public record TimelineEventRow(
        String eventType,
        Long eventId,
        LocalDateTime occurredAt,
        String queueName,
        String agentName,
        BigDecimal npsScore,
        String dispositionLabel) {
}
