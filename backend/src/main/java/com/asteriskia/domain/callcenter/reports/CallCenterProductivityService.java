package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.insights.AgentReportAggregationService;
import com.asteriskia.domain.insights.AgentReportContent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterProductivityService — "Produtividade do agente" (Fase 27): resumo de volume/TMA/NPS
 * (agregado diário, Fase 9b), timeline de login/pausa/logout ({@code cc_agent_states}, Fase 4) e
 * pontos fortes/de melhoria reaproveitados da análise já existente da Fase 8 — {@code não gera
 * nenhuma IA nova}, só lê o agregado determinístico já calculado por
 * {@link AgentReportAggregationService}. Sem cooldown/persistência — consulta on-the-fly, mesmo
 * espírito de {@link CallCenterReportsQueryService}.
 */
@Service
@RequiredArgsConstructor
public class CallCenterProductivityService {

    private static final String SOURCE = "callcenter";
    private static final int TOP_ITEMS_LIMIT = 3;

    private final CcAgentRepository agentRepository;
    private final CcAggAgentDailyRepository aggRepository;
    private final CcAgentStateRepository stateRepository;
    private final AgentReportAggregationService agentReportAggregationService;

    @Transactional(readOnly = true)
    public AgentProductivityReport build(Long agentId, LocalDate from, LocalDate to) {
        CcAgent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agente não encontrado"));

        List<CcAggAgentDaily> aggRows = aggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(agentId, from, to);
        AgentProductivityReport.Resumo resumo = summarizeResumo(aggRows);

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);
        List<AgentProductivityReport.StateEntry> timeline = stateRepository.findOverlapping(agentId, fromDt, toDt).stream()
                .sorted(Comparator.comparing(CcAgentState::getStartedAt))
                .map(s -> new AgentProductivityReport.StateEntry(
                        s.getState().name(),
                        s.getPauseReason() != null ? s.getPauseReason().getLabel() : null,
                        s.getStartedAt(),
                        s.getEndedAt()))
                .toList();

        AgentReportContent content = agentReportAggregationService.buildAggregate(agent.getName(), SOURCE, from, to);
        List<AgentReportContent.ItemAverage> notaPorItem = content.aggregate().notaPorItem();
        List<String> pontosFortes = topItems(notaPorItem, true);
        List<String> pontosMelhoria = topItems(notaPorItem, false);

        return new AgentProductivityReport(
                agent.getId(), agent.getName(), resumo, timeline,
                content.aggregate(), content.achadosGraves(), pontosFortes, pontosMelhoria);
    }

    private List<String> topItems(List<AgentReportContent.ItemAverage> items, boolean highest) {
        Comparator<AgentReportContent.ItemAverage> byMedia =
                Comparator.comparing(AgentReportContent.ItemAverage::media, Comparator.nullsLast(Comparator.naturalOrder()));
        if (highest) {
            byMedia = byMedia.reversed();
        }
        return items.stream()
                .filter(i -> i.media() != null)
                .sorted(byMedia)
                .limit(TOP_ITEMS_LIMIT)
                .map(AgentReportContent.ItemAverage::pergunta)
                .toList();
    }

    private AgentProductivityReport.Resumo summarizeResumo(List<CcAggAgentDaily> rows) {
        if (rows.isEmpty()) {
            return new AgentProductivityReport.Resumo(0, 0, null, null, null, null, 0, 0, 0, 0);
        }
        int totalAtendidas = rows.stream().mapToInt(CcAggAgentDaily::getAnswered).sum();
        int totalRealizadas = rows.stream().mapToInt(r -> r.getOutboundPlaced() != null ? r.getOutboundPlaced() : 0).sum();
        long occupiedSeconds = rows.stream().mapToLong(r -> r.getOccupiedSeconds().longValue()).sum();
        long availableSeconds = rows.stream().mapToLong(r -> r.getAvailableSeconds().longValue()).sum();
        long pausedSeconds = rows.stream().mapToLong(r -> r.getPausedSeconds().longValue()).sum();
        long offlineSeconds = rows.stream().mapToLong(r -> r.getOfflineSeconds().longValue()).sum();

        BigDecimal avgTalkSeconds = weightedAverage(rows, CcAggAgentDaily::getAvgTalkSeconds, CcAggAgentDaily::getAnswered);
        BigDecimal avgOutboundTalkSeconds = weightedAverage(
                rows, CcAggAgentDaily::getAvgOutboundTalkSeconds, r -> r.getOutboundPlaced() != null ? r.getOutboundPlaced() : 0);
        BigDecimal npsMedio = weightedAverage(rows, CcAggAgentDaily::getAvgNpsScore, CcAggAgentDaily::getAnswered);

        long occupancyDenominator = occupiedSeconds + availableSeconds;
        BigDecimal occupancyPct = occupancyDenominator == 0
                ? null
                : BigDecimal.valueOf(occupiedSeconds)
                        .divide(BigDecimal.valueOf(occupancyDenominator), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        return new AgentProductivityReport.Resumo(
                totalAtendidas, totalRealizadas, avgTalkSeconds, avgOutboundTalkSeconds, npsMedio, occupancyPct,
                occupiedSeconds, availableSeconds, pausedSeconds, offlineSeconds);
    }

    private BigDecimal weightedAverage(
            List<CcAggAgentDaily> rows, Function<CcAggAgentDaily, BigDecimal> valueFn, Function<CcAggAgentDaily, Integer> weightFn) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        long totalWeight = 0;
        for (CcAggAgentDaily row : rows) {
            BigDecimal value = valueFn.apply(row);
            Integer weight = weightFn.apply(row);
            if (value == null || weight == null || weight == 0) {
                continue;
            }
            weightedSum = weightedSum.add(value.multiply(BigDecimal.valueOf(weight)));
            totalWeight += weight;
        }
        return totalWeight == 0 ? null : weightedSum.divide(BigDecimal.valueOf(totalWeight), 2, RoundingMode.HALF_UP);
    }
}
