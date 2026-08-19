package com.asteriskia.domain.callcenter.wfm;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueMemberRepository;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import java.time.Instant;
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

    private final CcQueueRepository queueRepository;
    private final CcQueueMemberRepository queueMemberRepository;
    private final CcQueueWfmForecastRepository forecastRepository;
    private final ErlangCCalculator erlangCCalculator;

    @Transactional
    public List<WfmForecastDto> generateForecastForQueue(Long queueId, int horizonMinutes) {
        CcQueue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Fila não encontrada: ID " + queueId));

        int activeAgents = queueMemberRepository.findByQueueId(queueId).size();
        if (activeAgents <= 0) {
            activeAgents = 1; // Fallback mínimo de 1 agente
        }

        // Histórico recente ou baseline da fila
        double baseCallsPerHour = 20.0;
        double baseAhtSeconds = (queue.getTimeoutSeconds() != null && queue.getTimeoutSeconds() > 0)
                ? (double) queue.getTimeoutSeconds() : 180.0;
        double targetSla = 80.0;
        double targetTimeSec = 20.0;

        List<WfmForecastDto> results = new ArrayList<>();
        Instant now = Instant.now();

        int intervals = Math.max(1, horizonMinutes / 15);
        for (int i = 1; i <= intervals; i++) {
            Instant forecastTime = now.plus(15L * i, ChronoUnit.MINUTES);

            // Fator de oscilação / tendência EWMA simplificada por intervalo
            double trendMultiplier = 1.0 + (0.05 * (i % 3));
            double projectedCallsPerHour = baseCallsPerHour * trendMultiplier;
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
                    .algorithm("ERLANG_C_EWMA")
                    .createdAt(now)
                    .build();

            forecast = forecastRepository.save(forecast);
            results.add(toDto(forecast));
        }

        return results;
    }

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
