package com.asteriskia.domain;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

/**
 * BusinessDayCalculator — soma dias úteis (sábado/domingo não contam) a um instante,
 * usado pelo cooldown de relatórios de performance (Fase 2 do Quality Management, V39).
 * Sem calendário de feriados no MVP — decisão do plano, ver
 * .claude/plans/insights-quality-management.plan.md.
 */
public final class BusinessDayCalculator {

    private BusinessDayCalculator() {}

    /** Retorna {@code from} avançado em {@code businessDays} dias úteis (sábado/domingo pulados). */
    public static LocalDateTime addBusinessDays(LocalDateTime from, int businessDays) {
        LocalDateTime result = from;
        int added = 0;
        while (added < businessDays) {
            result = result.plusDays(1);
            if (!isWeekend(result)) {
                added++;
            }
        }
        return result;
    }

    private static boolean isWeekend(LocalDateTime dateTime) {
        DayOfWeek day = dateTime.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
