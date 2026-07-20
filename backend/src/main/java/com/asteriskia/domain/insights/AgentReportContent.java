package com.asteriskia.domain.insights;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * AgentReportContent — estrutura persistida em agent_performance_reports.content_json
 * (Fase 2 do Quality Management, V39). O agregado é sempre calculado em SQL/Java no
 * momento do pedido (nunca pelo LLM); a narrativa é preenchida depois pelo serviço
 * Python a partir desse agregado já pronto — mesmo princípio de EvaluationService: o
 * LLM só produz texto, nunca números.
 */
public record AgentReportContent(
        Aggregate aggregate,
        List<Finding> achadosGraves,
        Narrative narrative
) {

    public record Aggregate(
            long totalChamadas,
            BigDecimal notaMedia,
            long autoFails,
            List<ItemAverage> notaPorItem,
            Map<String, Long> achadosPorTipo
    ) {}

    public record ItemAverage(Long itemId, String pergunta, BigDecimal media) {}

    public record Finding(String tipo, String descricao, String trechoReferencia, String prioridade) {}

    /** Preenchida pelo serviço Python após a chamada ao LLM — null enquanto status != done. */
    public record Narrative(
            List<String> pontosFortes,
            List<String> pontosMelhoria,
            List<String> recomendacoes,
            String comparacaoTextual
    ) {}
}
