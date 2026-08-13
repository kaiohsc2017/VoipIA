package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.interaction.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterAgentAggregationService — agregado diário por agente de voz (sub-fase 9b do plano
 * modulo-callcenter-omnicanal.plan.md). Volume/TMA vêm de {@code cc_interactions} (mesma fonte
 * da 9a); ocupação/disponibilidade vêm de {@code cc_agent_states} (Fase 4), somando a fração de
 * cada período de estado que cai dentro do dia agregado — um período pode cruzar a meia-noite
 * ou ainda estar aberto (agente no estado agora), então nunca é só "duração do período inteiro".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterAgentAggregationService {

    private static final long MAX_REPROCESS_DAYS = 400;

    private final CcAgentRepository agentRepository;
    private final CcAgentStateRepository agentStateRepository;
    private final CcInteractionRepository interactionRepository;
    private final CcAggAgentDailyRepository aggRepository;

    /** Recalcula o agregado de TODOS os agentes ativos para um dia — sempre reescreve o
     * registro inteiro (upsert), nunca soma incrementalmente em cima do que já existia. */
    @Transactional
    public void aggregateDate(LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        for (CcAgent agent : agentRepository.findByActiveTrue()) {
            aggregateAgentDate(agent, date, dayStart, dayEnd);
        }
        log.info("Agregado diário de agentes de voz recalculado para {}", date);
    }

    private void aggregateAgentDate(CcAgent agent, LocalDate date, LocalDateTime dayStart, LocalDateTime dayEnd) {
        Map<AgentState, Long> secondsByState = secondsInEachState(agent.getId(), dayStart, dayEnd);

        long occupiedSeconds = secondsByState.getOrDefault(AgentState.EM_ATENDIMENTO, 0L)
                + secondsByState.getOrDefault(AgentState.ACW, 0L);
        long availableSeconds = secondsByState.getOrDefault(AgentState.DISPONIVEL, 0L);
        long pausedSeconds = secondsByState.getOrDefault(AgentState.PAUSA, 0L);
        long offlineSeconds = secondsByState.getOrDefault(AgentState.OFFLINE, 0L);

        long occupancyDenominator = occupiedSeconds + availableSeconds;
        BigDecimal occupancyPct = occupancyDenominator == 0
                ? null
                : BigDecimal.valueOf(occupiedSeconds)
                        .divide(BigDecimal.valueOf(occupancyDenominator), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        // Fase 23: findByAgentIdAndQueuedAtBetween traz os dois sentidos (INBOUND/OUTBOUND) —
        // sem o corte por direção abaixo, uma chamada de saída se misturaria no receptivo.
        List<CcInteraction> interactions = interactionRepository.findByAgentIdAndQueuedAtBetween(
                agent.getId(), dayStart, dayEnd);
        List<CcInteraction> inbound = interactions.stream()
                .filter(i -> i.getDirection() == Direction.INBOUND)
                .toList();
        List<CcInteraction> outbound = interactions.stream()
                .filter(i -> i.getDirection() == Direction.OUTBOUND)
                .toList();

        List<CcInteraction> answered = inbound.stream().filter(i -> i.getAnsweredAt() != null).toList();
        BigDecimal avgTalkSeconds = average(answered.stream()
                .filter(i -> i.getEndedAt() != null)
                .map(i -> Duration.between(i.getAnsweredAt(), i.getEndedAt()).toSeconds())
                .toList());

        List<CcInteraction> outboundAnswered = outbound.stream().filter(i -> i.getAnsweredAt() != null).toList();
        BigDecimal avgOutboundTalkSeconds = average(outboundAnswered.stream()
                .filter(i -> i.getEndedAt() != null)
                .map(i -> Duration.between(i.getAnsweredAt(), i.getEndedAt()).toSeconds())
                .toList());

        CcAggAgentDaily agg = aggRepository.findByAgentIdAndDate(agent.getId(), date)
                .orElseGet(() -> CcAggAgentDaily.builder().agent(agent).date(date).build());
        agg.setAgent(agent);
        agg.setBusinessUnit(agent.getBusinessUnit());
        agg.setAnswered(answered.size());
        agg.setAvgTalkSeconds(avgTalkSeconds);
        agg.setOutboundPlaced(outboundAnswered.size());
        agg.setAvgOutboundTalkSeconds(avgOutboundTalkSeconds);
        agg.setOccupiedSeconds((int) occupiedSeconds);
        agg.setAvailableSeconds((int) availableSeconds);
        agg.setPausedSeconds((int) pausedSeconds);
        agg.setOfflineSeconds((int) offlineSeconds);
        agg.setOccupancyPct(occupancyPct);
        agg.setAvgNpsScore(averageNpsScore(interactions));
        agg.setComputedAt(LocalDateTime.now());
        aggRepository.save(agg);
    }

    /** Soma, por estado, os segundos que o agente passou nesse estado dentro de
     * [dayStart, dayEnd) — cada período de {@code CcAgentState} é recortado (clip) pra dentro
     * dessa janela antes de somar, porque um período pode ter começado num dia anterior,
     * terminar num dia seguinte, ou ainda estar aberto (endedAt null = "vale até agora"). */
    private Map<AgentState, Long> secondsInEachState(Long agentId, LocalDateTime dayStart, LocalDateTime dayEnd) {
        Map<AgentState, Long> totals = new EnumMap<>(AgentState.class);
        LocalDateTime now = LocalDateTime.now();
        for (CcAgentState period : agentStateRepository.findOverlapping(agentId, dayStart, dayEnd)) {
            LocalDateTime periodEnd = period.getEndedAt() != null ? period.getEndedAt() : now;
            LocalDateTime overlapStart = period.getStartedAt().isAfter(dayStart) ? period.getStartedAt() : dayStart;
            LocalDateTime overlapEnd = periodEnd.isBefore(dayEnd) ? periodEnd : dayEnd;
            if (overlapEnd.isAfter(overlapStart)) {
                long seconds = Duration.between(overlapStart, overlapEnd).toSeconds();
                totals.merge(period.getState(), seconds, Long::sum);
            }
        }
        return totals;
    }

    /** Reprocessa um intervalo de dias (inclusive) — usado pelo endpoint manual de
     * reprocessamento. Mesmo limite de segurança e mesma convenção de
     * {@link ResponseStatusException} da 9a ({@code CallCenterQueueAggregationService}). */
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

    private BigDecimal average(List<Long> secondsValues) {
        if (secondsValues.isEmpty()) {
            return null;
        }
        long sum = secondsValues.stream().mapToLong(Long::longValue).sum();
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(secondsValues.size()), 2, RoundingMode.HALF_UP);
    }

    /** Fase 21 — média de {@code nps_score} das interações do agente no dia (INBOUND+OUTBOUND)
     * que têm nota; ignora as sem pesquisa/sem nota ainda. */
    private BigDecimal averageNpsScore(List<CcInteraction> interactions) {
        var scores = interactions.stream().map(CcInteraction::getNpsScore).filter(java.util.Objects::nonNull).toList();
        if (scores.isEmpty()) {
            return null;
        }
        var sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(scores.size()), 1, RoundingMode.HALF_UP);
    }
}
