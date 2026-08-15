package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecution;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionRepository;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStep;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStepRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterFlowAggregationService — agregado diário de volume/desfecho/abandono-por-nó de
 * execuções de fluxo visual (sub-fase 9c.1 do plano modulo-callcenter-omnicanal.plan.md). Mesmo
 * padrão de upsert-por-(flow,date) de {@link CallCenterQueueAggregationService}/
 * {@link CallCenterAgentAggregationService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterFlowAggregationService {

    private static final long MAX_REPROCESS_DAYS = 400;

    private final CcFlowRepository flowRepository;
    private final CcFlowExecutionRepository executionRepository;
    private final CcFlowExecutionStepRepository stepRepository;
    private final CcAggFlowDailyRepository aggRepository;
    private final CcAggFlowNodeDailyRepository nodeAggRepository;

    @Transactional
    public void aggregateDate(LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.atTime(LocalTime.MAX);
        for (CcFlow flow : flowRepository.findByActiveTrue()) {
            aggregateFlowDate(flow, date, from, to);
        }
        log.info("Agregado diário de fluxo/URA recalculado para {}", date);
    }

    private void aggregateFlowDate(CcFlow flow, LocalDate date, LocalDateTime from, LocalDateTime to) {
        List<CcFlowExecution> executions = executionRepository.findByFlowIdAndStartedAtBetween(flow.getId(), from, to);

        int completed = 0;
        int transferredQueue = 0;
        int transferredExtension = 0;
        int abandoned = 0;
        int errored = 0;
        long durationSum = 0;
        int durationCount = 0;
        for (CcFlowExecution execution : executions) {
            String outcome = execution.getOutcome();
            if ("COMPLETED".equals(outcome)) {
                completed++;
            } else if ("TRANSFERRED_QUEUE".equals(outcome)) {
                transferredQueue++;
            } else if ("TRANSFERRED_EXTENSION".equals(outcome)) {
                transferredExtension++;
            } else if ("ERROR".equals(outcome)) {
                errored++;
            } else {
                // ABANDONED explícito, ou ainda sem outcome (execução em aberto) — em ambos os
                // casos a chamada não teve um desfecho de sucesso, conta como abandono.
                abandoned++;
            }
            if (execution.getEndedAt() != null) {
                durationSum += Duration.between(execution.getStartedAt(), execution.getEndedAt()).toSeconds();
                durationCount++;
            }
        }
        BigDecimal avgDurationSeconds = durationCount == 0
                ? null
                : BigDecimal.valueOf(durationSum)
                        .divide(BigDecimal.valueOf(durationCount), 2, RoundingMode.HALF_UP);

        CcAggFlowDaily agg = aggRepository.findByFlowIdAndDate(flow.getId(), date)
                .orElseGet(() -> CcAggFlowDaily.builder().flow(flow).date(date).build());
        agg.setFlow(flow);
        agg.setBusinessUnit(flow.getBusinessUnit());
        agg.setExecutions(executions.size());
        agg.setCompleted(completed);
        agg.setTransferredQueue(transferredQueue);
        agg.setTransferredExtension(transferredExtension);
        agg.setAbandoned(abandoned);
        agg.setErrored(errored);
        agg.setAvgDurationSeconds(avgDurationSeconds);
        agg.setComputedAt(LocalDateTime.now());
        aggRepository.save(agg);

        aggregateNodesForFlowDate(flow, date, from, to, executions);
    }

    private void aggregateNodesForFlowDate(
            CcFlow flow, LocalDate date, LocalDateTime from, LocalDateTime to, List<CcFlowExecution> executions) {
        if (executions.isEmpty()) {
            return;
        }
        Map<Long, CcFlowExecution> executionsById = new HashMap<>();
        for (CcFlowExecution execution : executions) {
            executionsById.put(execution.getId(), execution);
        }
        List<CcFlowExecutionStep> steps = stepRepository.findByExecutionIdInAndEnteredAtBetween(
                List.copyOf(executionsById.keySet()), from, to);

        record NodeKey(String nodeId, String nodeType) {}
        Map<NodeKey, int[]> counters = new HashMap<>(); // [entries, abandonedHere]
        for (CcFlowExecutionStep step : steps) {
            NodeKey key = new NodeKey(step.getNodeId(), step.getNodeType());
            int[] counter = counters.computeIfAbsent(key, k -> new int[2]);
            counter[0]++;
            CcFlowExecution execution = executionsById.get(step.getExecution().getId());
            boolean diedHere = execution != null
                    && step.getNodeId().equals(execution.getLastNodeId())
                    && !"COMPLETED".equals(execution.getOutcome())
                    && !"TRANSFERRED_QUEUE".equals(execution.getOutcome())
                    && !"TRANSFERRED_EXTENSION".equals(execution.getOutcome());
            if (diedHere) {
                counter[1]++;
            }
        }

        counters.forEach((key, counter) -> {
            CcAggFlowNodeDaily nodeAgg = nodeAggRepository.findByFlowIdAndNodeIdAndDate(flow.getId(), key.nodeId(), date)
                    .orElseGet(() -> CcAggFlowNodeDaily.builder().flow(flow).nodeId(key.nodeId()).date(date).build());
            nodeAgg.setFlow(flow);
            nodeAgg.setNodeType(key.nodeType());
            nodeAgg.setEntries(counter[0]);
            nodeAgg.setAbandonedHere(counter[1]);
            nodeAgg.setComputedAt(LocalDateTime.now());
            nodeAggRepository.save(nodeAgg);
        });
    }

    /** Ver {@code CallCenterQueueAggregationService.reprocessRange} — mesma convenção de
     * {@link ResponseStatusException} e mesmo teto de dias. */
    public void reprocessRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data final não pode ser anterior à inicial.");
        }
        if (Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() > MAX_REPROCESS_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Intervalo maior que " + MAX_REPROCESS_DAYS + " dias — reprocesse em lotes menores.");
        }
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            aggregateDate(date);
        }
    }
}
