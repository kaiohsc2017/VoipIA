package com.asteriskia.domain.callcenter.desktop;

import java.math.BigDecimal;

/**
 * DesktopSummaryView — resumo do próprio dia do agente (Fase 22). {@code avgTalkSeconds} é nulo
 * quando nenhuma chamada de hoje tem {@code answeredAt}/{@code endedAt} preenchidos ainda.
 */
public record DesktopSummaryView(
        int callsAnsweredToday,
        Integer avgTalkSeconds,
        long loggedSeconds,
        long pauseSeconds,
        Double occupancyPct,
        BigDecimal avgNpsScore,
        BigDecimal adherencePct,
        Double comparedTo7dAvgTalkPct) {}
