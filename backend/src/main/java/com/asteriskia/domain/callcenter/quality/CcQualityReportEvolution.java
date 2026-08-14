package com.asteriskia.domain.callcenter.quality;

import java.math.BigDecimal;
import java.util.List;

/** CcQualityReportEvolution — comparação item a item contra a execução anterior no mesmo escopo
 * (Fase 26). {@code null} quando não há execução anterior (primeira do escopo). */
public record CcQualityReportEvolution(
        BigDecimal notaMediaAnterior,
        BigDecimal notaMediaDelta,
        List<ItemDelta> itens) {

    public record ItemDelta(Long itemId, String pergunta, BigDecimal mediaAnterior, BigDecimal mediaAtual, BigDecimal delta) {}
}
