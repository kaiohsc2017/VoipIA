package com.asteriskia.domain.callcenter.copilot;

import java.time.LocalDateTime;

/**
 * ContactHistoryItem — um item da lista unificada voz+chat de contatos anteriores do mesmo
 * {@code resolved_ad_sam} (Fase 16.1), consumido pelo painel do agente e, em texto resumido, pelo
 * prompt do copiloto de IA (Fase 16.2).
 */
public record ContactHistoryItem(
        String channel, // "voz" | "chat"
        Long referenceId, // id de cc_interactions ou cc_chat_sessions
        String queueName,
        String agentName,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        String dispositionLabel) {}
