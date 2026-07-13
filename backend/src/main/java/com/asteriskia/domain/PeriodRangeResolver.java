package com.asteriskia.domain;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * PeriodRangeResolver — Resolve o período "today|week|month|all" usado nos
 * indicadores agregados (StatsController) e nas exportações CSV (ReportController)
 * para a mesma janela [from, now).
 */
public final class PeriodRangeResolver {

    // Data mínima usada como início de janela para o período "all" — cobre todo o
    // histórico sem precisar de MIN(call_date) em query extra.
    private static final LocalDateTime EPOCH_START = LocalDateTime.of(2000, 1, 1, 0, 0);

    private PeriodRangeResolver() {}

    public static LocalDateTime[] resolve(String period) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = switch (period) {
            case "week" -> now.truncatedTo(ChronoUnit.DAYS).minusDays(now.getDayOfWeek().getValue() - 1);
            case "month" -> now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            case "all" -> EPOCH_START;
            default -> now.truncatedTo(ChronoUnit.DAYS); // today
        };
        return new LocalDateTime[]{from, now};
    }

    /**
     * Janela imediatamente anterior, com a mesma duração de [from, to] — usada para
     * calcular tendência período-a-período (ex: "▲12% vs. período anterior") sem
     * duplicar a lógica de limites de "today/week/month" no lado do cliente.
     */
    public static LocalDateTime[] previous(LocalDateTime[] range) {
        LocalDateTime from = range[0], to = range[1];
        java.time.Duration length = java.time.Duration.between(from, to);
        return new LocalDateTime[]{from.minus(length), from};
    }
}
