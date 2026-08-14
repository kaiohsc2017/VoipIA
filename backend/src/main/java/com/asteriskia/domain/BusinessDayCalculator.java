package com.asteriskia.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * BusinessDayCalculator — soma dias úteis (sábado/domingo não contam) a um instante,
 * usado pelo cooldown de relatórios de performance (Fase 2 do Quality Management, V39) e do
 * relatório de qualidade do Call Center (Fase 26). O overload sem feriados manteve o
 * comportamento original (nenhum consumidor existente foi afetado); feriados (tabela
 * {@code cc_holidays}, Fase 26) são opcionais via o overload com {@code Set<LocalDate>}.
 */
public final class BusinessDayCalculator {

    private BusinessDayCalculator() {}

    /** Retorna {@code from} avançado em {@code businessDays} dias úteis (sábado/domingo pulados,
     * sem feriado — mantido para não alterar o comportamento dos consumidores já existentes). */
    public static LocalDateTime addBusinessDays(LocalDateTime from, int businessDays) {
        return addBusinessDays(from, businessDays, Set.of());
    }

    /** Mesma soma, pulando também as datas do calendário de feriados informado. */
    public static LocalDateTime addBusinessDays(LocalDateTime from, int businessDays, Set<LocalDate> holidays) {
        LocalDateTime result = from;
        int added = 0;
        while (added < businessDays) {
            result = result.plusDays(1);
            if (!isWeekend(result) && !holidays.contains(result.toLocalDate())) {
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
