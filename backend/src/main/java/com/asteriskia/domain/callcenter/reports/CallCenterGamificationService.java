package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterGamificationService — ranking de agentes por NPS médio (Fase 27 do plano
 * callcenter-parte-iii-revisado). Lê direto {@code cc_agg_agent_daily} (Fase 9b) — sem
 * persistência própria, sem cooldown: é consulta agregada sobre dado que já existe, no mesmo
 * espírito de {@link CallCenterReportsQueryService}.
 */
@Service
@RequiredArgsConstructor
public class CallCenterGamificationService {

    private static final int DEFAULT_MIN_CALLS = 5;

    private final CcAggAgentDailyRepository aggRepository;

    @Transactional(readOnly = true)
    public GamificationReport rank(LocalDate from, LocalDate to, Integer minCallsParam) {
        int minCalls = minCallsParam != null && minCallsParam > 0 ? minCallsParam : DEFAULT_MIN_CALLS;

        List<CcAggAgentDaily> rows = aggRepository.findByDateBetweenOrderByAgentIdAscDateAsc(from, to);
        Map<Long, List<CcAggAgentDaily>> byAgent = new LinkedHashMap<>();
        for (CcAggAgentDaily row : rows) {
            byAgent.computeIfAbsent(row.getAgent().getId(), k -> new ArrayList<>()).add(row);
        }

        List<AgentGamificationRow> all = byAgent.values().stream().map(this::summarize).toList();

        Comparator<AgentGamificationRow> byNpsDesc =
                Comparator.comparing(AgentGamificationRow::npsMedio, Comparator.nullsLast(Comparator.reverseOrder()));

        List<AgentGamificationRow> eligible = all.stream()
                .filter(r -> r.totalAtendidas() >= minCalls)
                .sorted(byNpsDesc)
                .toList();
        List<AgentGamificationRow> ranking = new ArrayList<>();
        int position = 1;
        for (AgentGamificationRow row : eligible) {
            ranking.add(new AgentGamificationRow(
                    position++, row.agentId(), row.agentName(), row.totalAtendidas(), row.totalRealizadas(), row.npsMedio()));
        }

        List<AgentGamificationRow> belowMinimum = all.stream()
                .filter(r -> r.totalAtendidas() < minCalls)
                .sorted(byNpsDesc)
                .toList();

        return new GamificationReport(minCalls, ranking, belowMinimum);
    }

    private AgentGamificationRow summarize(List<CcAggAgentDaily> rows) {
        Long agentId = rows.get(0).getAgent().getId();
        String agentName = rows.get(0).getAgent().getName();
        int totalAtendidas = rows.stream().mapToInt(CcAggAgentDaily::getAnswered).sum();
        int totalRealizadas = rows.stream()
                .mapToInt(r -> r.getOutboundPlaced() != null ? r.getOutboundPlaced() : 0)
                .sum();
        BigDecimal npsMedio = weightedAverageNps(rows);
        return new AgentGamificationRow(null, agentId, agentName, totalAtendidas, totalRealizadas, npsMedio);
    }

    /** Pondera o NPS médio de cada dia pelo volume de atendidas daquele dia — nunca a média
     * simples das médias diárias (mesmo racional de {@link CallCenterReportsQueryService}). */
    private BigDecimal weightedAverageNps(List<CcAggAgentDaily> rows) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        long totalWeight = 0;
        for (CcAggAgentDaily row : rows) {
            BigDecimal value = row.getAvgNpsScore();
            Integer weight = row.getAnswered();
            if (value == null || weight == null || weight == 0) {
                continue;
            }
            weightedSum = weightedSum.add(value.multiply(BigDecimal.valueOf(weight)));
            totalWeight += weight;
        }
        return totalWeight == 0 ? null : weightedSum.divide(BigDecimal.valueOf(totalWeight), 2, RoundingMode.HALF_UP);
    }
}
