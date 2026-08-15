package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;
import java.util.List;

/**
 * RecallAndDispositionSummary — rechamada 24h/7d e top tabulações de uma fila num período
 * (sub-fase 9c.4). "Painel" sobre o relatório de fila já existente (9a), sem tela/resource novo.
 */
public record RecallAndDispositionSummary(
        int totalReceived,
        int recall24hCount,
        BigDecimal recall24hRatePct,
        int recall7dCount,
        BigDecimal recall7dRatePct,
        List<DispositionCount> topDispositions) {

    public record DispositionCount(String label, long count) {
    }
}
