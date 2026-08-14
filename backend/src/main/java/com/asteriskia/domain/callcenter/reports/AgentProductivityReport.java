package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.insights.AgentReportContent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AgentProductivityReport — login/pausas/logout, volume e desempenho de um agente (Fase 27,
 * "Produtividade do agente"). {@code analise}/{@code achadosGraves}/{@code pontosFortes}/
 * {@code pontosMelhoria} reusam {@link com.asteriskia.domain.insights.AgentReportAggregationService}
 * (Fase 8) tal como já existe — nenhuma chamada de IA nova, nenhuma narrativa gerada aqui.
 */
public record AgentProductivityReport(
        Long agentId,
        String agentName,
        Resumo resumo,
        List<StateEntry> timeline,
        AgentReportContent.Aggregate analise,
        List<AgentReportContent.Finding> achadosGraves,
        List<String> pontosFortes,
        List<String> pontosMelhoria) {

    public record Resumo(
            int totalAtendidas,
            int totalRealizadas,
            BigDecimal avgTalkSeconds,
            BigDecimal avgOutboundTalkSeconds,
            BigDecimal npsMedio,
            BigDecimal occupancyPct,
            long occupiedSeconds,
            long availableSeconds,
            long pausedSeconds,
            long offlineSeconds) {}

    /** Um período de {@code cc_agent_states} — {@code endedAt} nulo significa que o agente
     * ainda está nesse estado no momento da consulta. */
    public record StateEntry(String state, String pauseReasonLabel, LocalDateTime startedAt, LocalDateTime endedAt) {}
}
