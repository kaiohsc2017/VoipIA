package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterQueueAggregationService — agregado diário por fila de voz (sub-fase 9a do plano
 * modulo-callcenter-omnicanal.plan.md). Escopo desta fatia: só {@code cc_interactions} (voz) —
 * agregados de agente/fluxo/chat ficam para fatias 9b/9c futuras.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterQueueAggregationService {

    private static final long MAX_REPROCESS_DAYS = 400;

    private final CcQueueRepository queueRepository;
    private final CcInteractionRepository interactionRepository;
    private final CcAggQueueDailyRepository aggRepository;

    /** Recalcula o agregado de TODAS as filas ativas para um dia — sempre reescreve o registro
     * inteiro (upsert), nunca soma incrementalmente em cima do que já existia. */
    @Transactional
    public void aggregateDate(LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.atTime(LocalTime.MAX);
        for (CcQueue queue : queueRepository.findByActiveTrue()) {
            aggregateQueueDate(queue, date, from, to);
        }
        log.info("Agregado diário de filas de voz recalculado para {}", date);
    }

    private void aggregateQueueDate(CcQueue queue, LocalDate date, LocalDateTime from, LocalDateTime to) {
        List<CcInteraction> interactions = interactionRepository.findByQueueIdAndQueuedAtBetween(
                queue.getId(), from, to);

        int received = interactions.size();
        List<CcInteraction> answered = interactions.stream().filter(i -> i.getAnsweredAt() != null).toList();
        // Abandonada = nunca atendida e já encerrada (não conta interação ainda em andamento na fila).
        int abandoned = (int) interactions.stream()
                .filter(i -> i.getAnsweredAt() == null && i.getEndedAt() != null)
                .count();

        BigDecimal avgWaitSeconds = average(answered.stream()
                .map(i -> Duration.between(i.getQueuedAt(), i.getAnsweredAt()).toSeconds())
                .toList());

        BigDecimal avgTalkSeconds = average(answered.stream()
                .filter(i -> i.getEndedAt() != null)
                .map(i -> Duration.between(i.getAnsweredAt(), i.getEndedAt()).toSeconds())
                .toList());

        int timeoutSeconds = queue.getTimeoutSeconds() != null ? queue.getTimeoutSeconds() : 0;
        long withinSla = answered.stream()
                .filter(i -> Duration.between(i.getQueuedAt(), i.getAnsweredAt()).toSeconds() <= timeoutSeconds)
                .count();
        BigDecimal serviceLevelPct = answered.isEmpty()
                ? null
                : BigDecimal.valueOf(withinSla)
                        .divide(BigDecimal.valueOf(answered.size()), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        CcAggQueueDaily agg = aggRepository.findByQueueIdAndDate(queue.getId(), date)
                .orElseGet(() -> CcAggQueueDaily.builder().queue(queue).date(date).build());
        agg.setQueue(queue);
        agg.setBusinessUnit(queue.getBusinessUnit());
        agg.setReceived(received);
        agg.setAnswered(answered.size());
        agg.setAbandoned(abandoned);
        agg.setAvgWaitSeconds(avgWaitSeconds);
        agg.setAvgTalkSeconds(avgTalkSeconds);
        agg.setServiceLevelPct(serviceLevelPct);
        agg.setAvgNpsScore(averageNpsScore(interactions));
        agg.setComputedAt(LocalDateTime.now());
        aggRepository.save(agg);
    }

    /** Fase 21 — média de {@code nps_score} das interações do dia que têm nota; ignora as sem
     * pesquisa/sem nota ainda (não é 0, é ausência de dado). */
    private BigDecimal averageNpsScore(List<CcInteraction> interactions) {
        var scores = interactions.stream().map(CcInteraction::getNpsScore).filter(java.util.Objects::nonNull).toList();
        if (scores.isEmpty()) {
            return null;
        }
        var sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(scores.size()), 1, RoundingMode.HALF_UP);
    }

    /** Reprocessa um intervalo de dias (inclusive) — usado pelo endpoint manual de
     * reprocessamento. Limite de segurança contra reprocessar anos por engano. Lança
     * {@link ResponseStatusException} (não {@code IllegalArgumentException}) por convenção deste
     * pacote (ver {@code CcChatService}) — o {@code GlobalExceptionHandler} trata
     * {@code ResponseStatusException} preservando status/mensagem; qualquer outra
     * {@code RuntimeException} vira 500 genérico sem detalhe, o que esconderia do usuário um erro
     * de validação de input que ele pode corrigir sozinho. */
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
}
