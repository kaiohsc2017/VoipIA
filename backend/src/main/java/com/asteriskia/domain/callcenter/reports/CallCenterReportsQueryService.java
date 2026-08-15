package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterReportsQueryService — lê {@code cc_agg_queue_daily} (fila, sub-fase 9a) e
 * {@code cc_agg_agent_daily} (agente, sub-fase 9b), agrupando em dia/semana/mês/ano. A
 * granularidade "day" é o dado bruto; as demais somam os dias do período e recalculam
 * médias/taxas ponderadas pelo volume de cada dia (nunca a média simples das médias diárias,
 * que distorceria dias/agentes de volumes bem diferentes).
 */
@Service
@RequiredArgsConstructor
public class CallCenterReportsQueryService {

    private final CcAggQueueDailyRepository aggRepository;
    private final CcAggAgentDailyRepository agentAggRepository;
    private final CcAggFlowDailyRepository flowAggRepository;
    private final CcAggFlowNodeDailyRepository flowNodeAggRepository;

    public enum Granularity { DAY, WEEK, MONTH, YEAR }

    @Transactional(readOnly = true)
    public List<QueuePeriodMetrics> queryQueue(Long queueId, LocalDate from, LocalDate to, Granularity granularity) {
        List<CcAggQueueDaily> rows = aggRepository.findByQueueIdAndDateBetweenOrderByDateAsc(queueId, from, to);
        return groupByPeriod(rows, granularity);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<QueuePeriodMetrics>> queryAllQueues(LocalDate from, LocalDate to, Granularity granularity) {
        List<CcAggQueueDaily> rows = aggRepository.findByDateBetweenOrderByQueueIdAscDateAsc(from, to);
        Map<Long, List<CcAggQueueDaily>> byQueue = new LinkedHashMap<>();
        for (CcAggQueueDaily row : rows) {
            byQueue.computeIfAbsent(row.getQueue().getId(), k -> new java.util.ArrayList<>()).add(row);
        }
        Map<Long, List<QueuePeriodMetrics>> result = new LinkedHashMap<>();
        byQueue.forEach((queueId, queueRows) -> result.put(queueId, groupByPeriod(queueRows, granularity)));
        return result;
    }

    @Transactional(readOnly = true)
    public QueuePeriodComparison compare(Long queueId, LocalDate periodAFrom, LocalDate periodATo,
                                          LocalDate periodBFrom, LocalDate periodBTo) {
        QueuePeriodMetrics a = summarize(queueId, periodAFrom, periodATo, "Período A");
        QueuePeriodMetrics b = summarize(queueId, periodBFrom, periodBTo, "Período B");
        return new QueuePeriodComparison(
                a, b,
                b.received() - a.received(),
                b.answered() - a.answered(),
                b.abandoned() - a.abandoned(),
                nullSafeSubtract(b.abandonRatePct(), a.abandonRatePct()),
                nullSafeSubtract(b.avgWaitSeconds(), a.avgWaitSeconds()),
                nullSafeSubtract(b.avgTalkSeconds(), a.avgTalkSeconds()),
                nullSafeSubtract(b.serviceLevelPct(), a.serviceLevelPct()));
    }

    private QueuePeriodMetrics summarize(Long queueId, LocalDate from, LocalDate to, String label) {
        List<CcAggQueueDaily> rows = aggRepository.findByQueueIdAndDateBetweenOrderByDateAsc(queueId, from, to);
        return combine(rows, label);
    }

    private List<QueuePeriodMetrics> groupByPeriod(List<CcAggQueueDaily> rows, Granularity granularity) {
        if (granularity == Granularity.DAY) {
            return rows.stream()
                    .map(r -> combine(List.of(r), r.getDate().toString()))
                    .toList();
        }
        Map<String, List<CcAggQueueDaily>> grouped = new LinkedHashMap<>();
        for (CcAggQueueDaily row : rows) {
            grouped.computeIfAbsent(periodLabel(row.getDate(), granularity), k -> new java.util.ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream()
                .map(e -> combine(e.getValue(), e.getKey()))
                .sorted(Comparator.comparing(QueuePeriodMetrics::periodLabel))
                .toList();
    }

    private String periodLabel(LocalDate date, Granularity granularity) {
        return switch (granularity) {
            case DAY -> date.toString();
            case WEEK -> {
                WeekFields wf = WeekFields.ISO;
                yield date.get(wf.weekBasedYear()) + "-W" + String.format(Locale.ROOT, "%02d", date.get(wf.weekOfWeekBasedYear()));
            }
            case MONTH -> YearMonth.from(date).toString();
            case YEAR -> String.valueOf(date.getYear());
        };
    }

    /** Combina N linhas diárias num único ponto — soma volumes, pondera médias/SLA pelo
     * volume de atendidas de cada dia (dias sem nenhuma atendida não entram no ponderador). */
    private QueuePeriodMetrics combine(List<CcAggQueueDaily> rows, String label) {
        if (rows.isEmpty()) {
            return new QueuePeriodMetrics(null, null, label, 0, 0, 0, null, null, null, null);
        }
        Long queueId = rows.get(0).getQueue().getId();
        String queueName = rows.get(0).getQueue().getDisplayName();

        int received = rows.stream().mapToInt(CcAggQueueDaily::getReceived).sum();
        int answered = rows.stream().mapToInt(CcAggQueueDaily::getAnswered).sum();
        int abandoned = rows.stream().mapToInt(CcAggQueueDaily::getAbandoned).sum();

        BigDecimal abandonRatePct = received == 0
                ? null
                : BigDecimal.valueOf(abandoned)
                        .divide(BigDecimal.valueOf(received), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        BigDecimal avgWaitSeconds = weightedAverage(rows, CcAggQueueDaily::getAvgWaitSeconds, CcAggQueueDaily::getAnswered);
        BigDecimal avgTalkSeconds = weightedAverage(rows, CcAggQueueDaily::getAvgTalkSeconds, CcAggQueueDaily::getAnswered);
        BigDecimal serviceLevelPct = weightedAverage(rows, CcAggQueueDaily::getServiceLevelPct, CcAggQueueDaily::getAnswered);

        return new QueuePeriodMetrics(queueId, queueName, label, received, answered, abandoned,
                abandonRatePct, avgWaitSeconds, avgTalkSeconds, serviceLevelPct);
    }

    // ─── Agente de voz (sub-fase 9b) ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AgentPeriodMetrics> queryAgent(Long agentId, LocalDate from, LocalDate to, Granularity granularity) {
        List<CcAggAgentDaily> rows = agentAggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(agentId, from, to);
        return groupByPeriodAgent(rows, granularity);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<AgentPeriodMetrics>> queryAllAgents(LocalDate from, LocalDate to, Granularity granularity) {
        List<CcAggAgentDaily> rows = agentAggRepository.findByDateBetweenOrderByAgentIdAscDateAsc(from, to);
        Map<Long, List<CcAggAgentDaily>> byAgent = new LinkedHashMap<>();
        for (CcAggAgentDaily row : rows) {
            byAgent.computeIfAbsent(row.getAgent().getId(), k -> new java.util.ArrayList<>()).add(row);
        }
        Map<Long, List<AgentPeriodMetrics>> result = new LinkedHashMap<>();
        byAgent.forEach((agentId, agentRows) -> result.put(agentId, groupByPeriodAgent(agentRows, granularity)));
        return result;
    }

    @Transactional(readOnly = true)
    public AgentPeriodComparison compareAgent(Long agentId, LocalDate periodAFrom, LocalDate periodATo,
                                               LocalDate periodBFrom, LocalDate periodBTo) {
        AgentPeriodMetrics a = summarizeAgent(agentId, periodAFrom, periodATo, "Período A");
        AgentPeriodMetrics b = summarizeAgent(agentId, periodBFrom, periodBTo, "Período B");
        return new AgentPeriodComparison(
                a, b,
                b.answered() - a.answered(),
                nullSafeSubtract(b.avgTalkSeconds(), a.avgTalkSeconds()),
                b.occupiedSeconds() - a.occupiedSeconds(),
                b.availableSeconds() - a.availableSeconds(),
                nullSafeSubtract(b.occupancyPct(), a.occupancyPct()));
    }

    private AgentPeriodMetrics summarizeAgent(Long agentId, LocalDate from, LocalDate to, String label) {
        List<CcAggAgentDaily> rows = agentAggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(agentId, from, to);
        return combineAgent(rows, label);
    }

    private List<AgentPeriodMetrics> groupByPeriodAgent(List<CcAggAgentDaily> rows, Granularity granularity) {
        if (granularity == Granularity.DAY) {
            return rows.stream()
                    .map(r -> combineAgent(List.of(r), r.getDate().toString()))
                    .toList();
        }
        Map<String, List<CcAggAgentDaily>> grouped = new LinkedHashMap<>();
        for (CcAggAgentDaily row : rows) {
            grouped.computeIfAbsent(periodLabel(row.getDate(), granularity), k -> new java.util.ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream()
                .map(e -> combineAgent(e.getValue(), e.getKey()))
                .sorted(Comparator.comparing(AgentPeriodMetrics::periodLabel))
                .toList();
    }

    /** Combina N linhas diárias num único ponto de agente — soma volumes/segundos por estado,
     * pondera TMA pelo volume de atendidas e ocupação pelo total de segundos logados
     * (ocupado + disponível) de cada dia — nunca a média simples das médias diárias. */
    private AgentPeriodMetrics combineAgent(List<CcAggAgentDaily> rows, String label) {
        if (rows.isEmpty()) {
            return new AgentPeriodMetrics(null, null, label, 0, null, 0, 0, 0, 0, null);
        }
        Long agentId = rows.get(0).getAgent().getId();
        String agentName = rows.get(0).getAgent().getName();

        int answered = rows.stream().mapToInt(CcAggAgentDaily::getAnswered).sum();
        long occupiedSeconds = rows.stream().mapToLong(r -> r.getOccupiedSeconds().longValue()).sum();
        long availableSeconds = rows.stream().mapToLong(r -> r.getAvailableSeconds().longValue()).sum();
        long pausedSeconds = rows.stream().mapToLong(r -> r.getPausedSeconds().longValue()).sum();
        long offlineSeconds = rows.stream().mapToLong(r -> r.getOfflineSeconds().longValue()).sum();

        BigDecimal avgTalkSeconds = weightedAverage(rows, CcAggAgentDaily::getAvgTalkSeconds, CcAggAgentDaily::getAnswered);

        long occupancyDenominator = occupiedSeconds + availableSeconds;
        BigDecimal occupancyPct = occupancyDenominator == 0
                ? null
                : BigDecimal.valueOf(occupiedSeconds)
                        .divide(BigDecimal.valueOf(occupancyDenominator), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        return new AgentPeriodMetrics(agentId, agentName, label, answered, avgTalkSeconds,
                occupiedSeconds, availableSeconds, pausedSeconds, offlineSeconds, occupancyPct);
    }

    // ─── Fluxo/URA (sub-fase 9c.1) ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FlowPeriodMetrics> queryFlow(Long flowId, LocalDate from, LocalDate to, Granularity granularity) {
        List<CcAggFlowDaily> rows = flowAggRepository.findByFlowIdAndDateBetweenOrderByDateAsc(flowId, from, to);
        return groupByPeriodFlow(rows, granularity);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<FlowPeriodMetrics>> queryAllFlows(LocalDate from, LocalDate to, Granularity granularity) {
        List<CcAggFlowDaily> rows = flowAggRepository.findByDateBetweenOrderByFlowIdAscDateAsc(from, to);
        Map<Long, List<CcAggFlowDaily>> byFlow = new LinkedHashMap<>();
        for (CcAggFlowDaily row : rows) {
            byFlow.computeIfAbsent(row.getFlow().getId(), k -> new java.util.ArrayList<>()).add(row);
        }
        Map<Long, List<FlowPeriodMetrics>> result = new LinkedHashMap<>();
        byFlow.forEach((flowId, flowRows) -> result.put(flowId, groupByPeriodFlow(flowRows, granularity)));
        return result;
    }

    /** Abandono por nó, somado no período — não é ponderado nem agrupado por granularidade
     * (diferente das métricas de volume), é uma soma simples dos registros diários do intervalo,
     * ordenada pela maior taxa de abandono primeiro para o supervisor ver logo o pior nó. */
    @Transactional(readOnly = true)
    public List<FlowNodeAbandonmentRow> queryFlowNodeAbandonment(Long flowId, LocalDate from, LocalDate to) {
        List<CcAggFlowNodeDaily> rows = flowNodeAggRepository.findByFlowIdAndDateBetween(flowId, from, to);
        Map<String, int[]> byNode = new LinkedHashMap<>(); // [entries, abandonedHere]
        Map<String, String> nodeTypeByNode = new LinkedHashMap<>();
        for (CcAggFlowNodeDaily row : rows) {
            int[] counter = byNode.computeIfAbsent(row.getNodeId(), k -> new int[2]);
            counter[0] += row.getEntries();
            counter[1] += row.getAbandonedHere();
            nodeTypeByNode.put(row.getNodeId(), row.getNodeType());
        }
        return byNode.entrySet().stream()
                .map(e -> {
                    int entries = e.getValue()[0];
                    int abandonedHere = e.getValue()[1];
                    BigDecimal rate = entries == 0
                            ? null
                            : BigDecimal.valueOf(abandonedHere)
                                    .divide(BigDecimal.valueOf(entries), 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .setScale(2, RoundingMode.HALF_UP);
                    return new FlowNodeAbandonmentRow(e.getKey(), nodeTypeByNode.get(e.getKey()), entries, abandonedHere, rate);
                })
                .sorted(Comparator.comparing(FlowNodeAbandonmentRow::abandonRatePct,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private List<FlowPeriodMetrics> groupByPeriodFlow(List<CcAggFlowDaily> rows, Granularity granularity) {
        if (granularity == Granularity.DAY) {
            return rows.stream()
                    .map(r -> combineFlow(List.of(r), r.getDate().toString()))
                    .toList();
        }
        Map<String, List<CcAggFlowDaily>> grouped = new LinkedHashMap<>();
        for (CcAggFlowDaily row : rows) {
            grouped.computeIfAbsent(periodLabel(row.getDate(), granularity), k -> new java.util.ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream()
                .map(e -> combineFlow(e.getValue(), e.getKey()))
                .sorted(Comparator.comparing(FlowPeriodMetrics::periodLabel))
                .toList();
    }

    private FlowPeriodMetrics combineFlow(List<CcAggFlowDaily> rows, String label) {
        if (rows.isEmpty()) {
            return new FlowPeriodMetrics(null, null, label, 0, 0, 0, 0, 0, 0, null, null);
        }
        Long flowId = rows.get(0).getFlow().getId();
        String flowName = rows.get(0).getFlow().getName();

        int executions = rows.stream().mapToInt(CcAggFlowDaily::getExecutions).sum();
        int completed = rows.stream().mapToInt(CcAggFlowDaily::getCompleted).sum();
        int transferredQueue = rows.stream().mapToInt(CcAggFlowDaily::getTransferredQueue).sum();
        int transferredExtension = rows.stream().mapToInt(CcAggFlowDaily::getTransferredExtension).sum();
        int abandoned = rows.stream().mapToInt(CcAggFlowDaily::getAbandoned).sum();
        int errored = rows.stream().mapToInt(CcAggFlowDaily::getErrored).sum();

        BigDecimal abandonRatePct = executions == 0
                ? null
                : BigDecimal.valueOf(abandoned)
                        .divide(BigDecimal.valueOf(executions), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        BigDecimal avgDurationSeconds = weightedAverage(rows, CcAggFlowDaily::getAvgDurationSeconds, CcAggFlowDaily::getExecutions);

        return new FlowPeriodMetrics(flowId, flowName, label, executions, completed, transferredQueue,
                transferredExtension, abandoned, errored, abandonRatePct, avgDurationSeconds);
    }

    private <T> BigDecimal weightedAverage(List<T> rows, Function<T, BigDecimal> valueFn, Function<T, Integer> weightFn) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        long totalWeight = 0;
        for (T row : rows) {
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

    private BigDecimal nullSafeSubtract(BigDecimal b, BigDecimal a) {
        if (b == null || a == null) {
            return null;
        }
        return b.subtract(a);
    }
}
