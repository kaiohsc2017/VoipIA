package com.asteriskia.domain.insights;

import java.math.BigDecimal;
import java.util.List;

/**
 * AgentReportEvolution — estrutura persistida em agent_performance_reports.evolution_json
 * (Fase 2 do Quality Management, V39). Delta numérico sempre calculado no Java a partir
 * do agregado deste relatório e do relatório anterior do mesmo agente (previousReportId)
 * — o LLM só narra o delta já pronto (campo comparacaoTextual em AgentReportContent.Narrative).
 */
public record AgentReportEvolution(
        Long previousReportId,
        boolean partial,
        BigDecimal deltaNotaMedia,
        List<ItemDelta> deltaPorItem
) {

    /** true quando os itens de ficha do relatório anterior não batem com os deste
     * (ficha foi trocada entre os dois períodos) — UI deve sinalizar "comparação parcial". */
    public record ItemDelta(Long itemId, String pergunta, BigDecimal anterior, BigDecimal atual, BigDecimal delta) {}
}
