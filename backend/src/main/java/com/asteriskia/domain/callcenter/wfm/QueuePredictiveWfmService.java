package com.asteriskia.domain.callcenter.wfm;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueMemberRepository;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.reports.CcAggQueueDaily;
import com.asteriskia.domain.callcenter.reports.CcAggQueueDailyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueuePredictiveWfmService {

    /** Janela de histórico consultada em {@code cc_agg_queue_daily} (Fase 9a) para calibrar o
     * volume/tendência de base do forecast. */
    private static final int HISTORY_LOOKBACK_DAYS = 28;

    /** Mínimo de dias com agregado diário existente para considerar o histórico confiável —
     * abaixo disso, cai no fallback conservador em vez de projetar sobre poucos pontos. */
    private static final int MIN_HISTORY_DAYS_WITH_DATA = 3;

    /** {@code cc_agg_queue_daily} guarda só o total recebido por DIA (Fase 9a), sem quebra por
     * hora — não há dado real de distribuição horária por fila. Dividir pelo dia inteiro (24h) é
     * uma simplificação deliberadamente conservadora (documentada, não fictícia) até que exista
     * um agregado por hora. */
    private static final double HOURS_PER_DAY_FOR_HOURLY_RATE = 24.0;

    /** Fallback conservador — só usado quando não há histórico suficiente (nunca inventado como
     * baseline "normal"; o algoritmo devolvido nesse caso deixa isso explícito). */
    private static final double FALLBACK_BASE_CALLS_PER_HOUR = 20.0;

    private static final double MIN_TREND_MULTIPLIER = 0.5;
    private static final double MAX_TREND_MULTIPLIER = 2.0;

    private static final String ALGORITHM_HISTORICO = "ERLANG_C_HISTORICO_28D";
    private static final String ALGORITHM_FALLBACK_SEM_HISTORICO = "ERLANG_C_FALLBACK_DADOS_INSUFICIENTES";

    private final CcQueueRepository queueRepository;
    private final CcQueueMemberRepository queueMemberRepository;
    private final CcQueueWfmForecastRepository forecastRepository;
    private final CcAggQueueDailyRepository aggQueueDailyRepository;
    private final ErlangCCalculator erlangCCalculator;

    @Transactional
    public List<WfmForecastDto> generateForecastForQueue(Long queueId, int horizonMinutes) {
        CcQueue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Fila não encontrada: ID " + queueId));

        long activeAgentsCount = queueMemberRepository.countByQueueId(queueId);
        int activeAgents = activeAgentsCount > 0 ? (int) activeAgentsCount : 1; // Fallback mínimo de 1 agente

        HistoricalBaseline baseline = computeHistoricalBaseline(queueId);
        double baseAhtSeconds = (queue.getTimeoutSeconds() != null && queue.getTimeoutSeconds() > 0)
                ? (double) queue.getTimeoutSeconds() : 180.0;
        double targetSla = 80.0;
        double targetTimeSec = 20.0;
        String algorithm = baseline.hasSufficientData() ? ALGORITHM_HISTORICO : ALGORITHM_FALLBACK_SEM_HISTORICO;

        List<WfmForecastDto> results = new ArrayList<>();
        Instant now = Instant.now();

        int intervals = Math.max(1, horizonMinutes / 15);
        for (int i = 1; i <= intervals; i++) {
            Instant forecastTime = now.plus(15L * i, ChronoUnit.MINUTES);

            double projectedCallsPerHour = baseline.baseCallsPerHour() * baseline.trendMultiplier();
            int predictedCalls = (int) Math.round((projectedCallsPerHour / 4.0)); // Intervalo de 15m

            double intensity = erlangCCalculator.calculateTrafficIntensity(projectedCallsPerHour, baseAhtSeconds);
            int required = erlangCCalculator.calculateRequiredAgents(projectedCallsPerHour, baseAhtSeconds, targetSla, targetTimeSec);
            double predictedSla = erlangCCalculator.calculateServiceLevel(activeAgents, intensity, baseAhtSeconds, targetTimeSec);
            boolean risk = predictedSla < targetSla;

            CcQueueWfmForecast forecast = CcQueueWfmForecast.builder()
                    .queue(queue)
                    .forecastTimestamp(forecastTime)
                    .intervalMinutes(15)
                    .predictedCallVolume(predictedCalls)
                    .predictedAhtSeconds((int) baseAhtSeconds)
                    .requiredAgents(required)
                    .currentScheduledAgents(activeAgents)
                    .predictedSlaPercent(Math.round(predictedSla * 100.0) / 100.0)
                    .targetSlaPercent(targetSla)
                    .slaBreachRisk(risk)
                    .algorithm(algorithm)
                    .createdAt(now)
                    .build();

            forecast = forecastRepository.save(forecast);
            results.add(toDto(forecast));
        }

        return results;
    }

    /**
     * Calcula a taxa de chamadas/hora e a tendência de base a partir do histórico real de
     * {@code cc_agg_queue_daily} (Fase 9a) dos últimos {@link #HISTORY_LOOKBACK_DAYS} dias. Sem
     * histórico suficiente, devolve o fallback conservador explícito (nunca um número fictício
     * disfarçado de dado real — o {@code algorithm} do forecast final sinaliza a diferença).
     */
    private HistoricalBaseline computeHistoricalBaseline(Long queueId) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(HISTORY_LOOKBACK_DAYS);
        LocalDate to = today.minusDays(1);
        List<CcAggQueueDaily> history =
                aggQueueDailyRepository.findByQueueIdAndDateBetweenOrderByDateAsc(queueId, from, to);

        if (history.size() < MIN_HISTORY_DAYS_WITH_DATA) {
            log.warn(
                    "WFM preditivo: histórico insuficiente para a fila {} ({} dia(s) de agregado nos últimos {} "
                            + "dias, mínimo exigido {}) — usando fallback conservador em vez de dado fictício.",
                    queueId, history.size(), HISTORY_LOOKBACK_DAYS, MIN_HISTORY_DAYS_WITH_DATA);
            return new HistoricalBaseline(FALLBACK_BASE_CALLS_PER_HOUR, 1.0, false);
        }

        double avgDailyCalls = history.stream()
                .mapToInt(h -> h.getReceived() != null ? h.getReceived() : 0)
                .average()
                .orElse(0.0);
        if (avgDailyCalls <= 0) {
            return new HistoricalBaseline(FALLBACK_BASE_CALLS_PER_HOUR, 1.0, false);
        }
        double baseCallsPerHour = avgDailyCalls / HOURS_PER_DAY_FOR_HOURLY_RATE;

        // Tendência: compara a média da segunda metade da janela com a primeira metade — reflete
        // crescimento/queda real de volume observado, nunca uma oscilação artificial por intervalo.
        int half = history.size() / 2;
        double firstHalfAvg = history.subList(0, half).stream()
                .mapToInt(h -> h.getReceived() != null ? h.getReceived() : 0)
                .average()
                .orElse(avgDailyCalls);
        double secondHalfAvg = history.subList(half, history.size()).stream()
                .mapToInt(h -> h.getReceived() != null ? h.getReceived() : 0)
                .average()
                .orElse(avgDailyCalls);
        double trend = firstHalfAvg > 0 ? secondHalfAvg / firstHalfAvg : 1.0;
        double clampedTrend = Math.max(MIN_TREND_MULTIPLIER, Math.min(MAX_TREND_MULTIPLIER, trend));

        return new HistoricalBaseline(baseCallsPerHour, clampedTrend, true);
    }

    /** Baseline calculado a partir do histórico real da fila (ou o fallback conservador quando
     * não há dado suficiente) — {@code hasSufficientData=false} sinaliza o segundo caso. */
    private record HistoricalBaseline(double baseCallsPerHour, double trendMultiplier, boolean hasSufficientData) {}

    @Transactional(readOnly = true)
    public List<WfmForecastDto> getRecentForecasts(Long queueId) {
        Instant since = Instant.now().minus(2, ChronoUnit.HOURS);
        return forecastRepository.findRecentByQueueId(queueId, since).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WfmForecastDto> getActiveBreachAlerts() {
        Instant since = Instant.now().minus(30, ChronoUnit.MINUTES);
        return forecastRepository.findActiveBreachRisks(since).stream()
                .map(this::toDto)
                .toList();
    }

    private WfmForecastDto toDto(CcQueueWfmForecast f) {
        return new WfmForecastDto(
                f.getId(),
                f.getQueue().getId(),
                f.getQueue().getName(),
                f.getForecastTimestamp(),
                f.getIntervalMinutes(),
                f.getPredictedCallVolume(),
                f.getPredictedAhtSeconds(),
                f.getRequiredAgents(),
                f.getCurrentScheduledAgents(),
                f.getPredictedSlaPercent(),
                f.getTargetSlaPercent(),
                f.getSlaBreachRisk(),
                f.getAlgorithm(),
                f.getCreatedAt()
        );
    }

    public record WfmForecastDto(
            Long id,
            Long queueId,
            String queueName,
            Instant forecastTimestamp,
            Integer intervalMinutes,
            Integer predictedCallVolume,
            Integer predictedAhtSeconds,
            Integer requiredAgents,
            Integer currentScheduledAgents,
            Double predictedSlaPercent,
            Double targetSlaPercent,
            Boolean slaBreachRisk,
            String algorithm,
            Instant createdAt
    ) {}
}
