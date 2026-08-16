package com.asteriskia.domain.callcenter.desktop;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DesktopScheduleView — turno de trabalho de hoje e porcentagem de aderência do agente.
 */
public record DesktopScheduleView(
        String shiftLabel,
        LocalDateTime shiftStart,
        LocalDateTime shiftEnd,
        Long scheduledSeconds,
        Long loggedSeconds,
        BigDecimal adherencePct,
        String adherenceStatus) {}
