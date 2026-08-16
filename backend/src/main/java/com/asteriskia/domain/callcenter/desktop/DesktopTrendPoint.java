package com.asteriskia.domain.callcenter.desktop;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DesktopTrendPoint — ponto diário na série histórica do agente (lido de cc_agg_agent_daily).
 */
public record DesktopTrendPoint(
        LocalDate date,
        Integer answeredCount,
        Integer avgTalkSeconds,
        Double occupancyPct,
        BigDecimal avgNpsScore) {}
