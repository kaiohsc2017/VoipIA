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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterReportsQueryService — lê {@code cc_agg_queue_daily} e agrupa em
 * dia/semana/mês/ano (sub-fase 9a). A granularidade "day" é o dado bruto; as demais somam os
 * dias do período e recalculam médias/nível de serviço ponderados pelo volume de cada dia
 * (nunca a média simples das médias diárias, que distorceria dias de volumes bem diferentes).
 */
@Service
@RequiredArgsConstructor
public class CallCenterReportsQueryService {

    private final CcAggQueueDailyRepository aggRepository;

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

    private BigDecimal weightedAverage(List<CcAggQueueDaily> rows,
                                        java.util.function.Function<CcAggQueueDaily, BigDecimal> valueFn,
                                        java.util.function.Function<CcAggQueueDaily, Integer> weightFn) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        long totalWeight = 0;
        for (CcAggQueueDaily row : rows) {
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
