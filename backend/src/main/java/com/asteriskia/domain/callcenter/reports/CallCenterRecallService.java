package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterRecallService — rechamada em 24h/7d e top tabulações de uma fila (sub-fase 9c.4 do
 * plano modulo-callcenter-omnicanal.plan.md). Painel calculado on-the-fly sobre
 * {@code cc_interactions} já existente — sem agregado persistido, sem tela/resource novo (mesmo
 * espírito de {@link CallCenterCustomerProfileService}, mas aqui o escopo é uma fila, não um
 * cliente).
 *
 * <p>"Rechamada" = o mesmo contato (ANI normalizado, {@link AniNormalizer}) já tinha entrado em
 * QUALQUER fila da operação antes desta interação, dentro da janela de 24h/7d — não só na mesma
 * fila, porque um cliente pode ligar de novo e cair numa fila diferente (transbordo, escolha de
 * URA diferente) e ainda assim ser uma rechamada do ponto de vista do cliente.
 *
 * <p>Sem paginação em banco na varredura do período (mesma decisão já aceita em
 * {@code CallCenterCustomerProfileService}/{@code CallCenterDetailReportService#searchChats} para
 * o volume desta VPS de dev).
 */
@Service
@RequiredArgsConstructor
public class CallCenterRecallService {

    private static final int TOP_DISPOSITIONS_LIMIT = 5;

    private final CcInteractionRepository interactionRepository;

    @Transactional(readOnly = true)
    public RecallAndDispositionSummary summarize(Long queueId, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);
        // Janela de retrospecto de 7 dias antes do início do período, pra detectar rechamada de
        // interações que entraram logo no começo do período informado.
        LocalDateTime lookbackFrom = fromDt.minusDays(7);

        List<CcInteraction> allInWindow = interactionRepository.findByQueuedAtBetween(lookbackFrom, toDt);

        Map<String, List<LocalDateTime>> priorContactsByAni = allInWindow.stream()
                .filter(i -> i.getAni() != null && !i.getAni().isBlank())
                .collect(Collectors.groupingBy(
                        i -> AniNormalizer.normalize(i.getAni()),
                        Collectors.mapping(CcInteraction::getQueuedAt, Collectors.toList())));

        List<CcInteraction> cohort = allInWindow.stream()
                .filter(i -> queueId.equals(i.getQueue() != null ? i.getQueue().getId() : null))
                .filter(i -> !i.getQueuedAt().isBefore(fromDt) && !i.getQueuedAt().isAfter(toDt))
                .toList();

        int recall24h = 0;
        int recall7d = 0;
        for (CcInteraction interaction : cohort) {
            String key = AniNormalizer.normalize(interaction.getAni());
            List<LocalDateTime> priorTimes = key != null ? priorContactsByAni.getOrDefault(key, List.of()) : List.of();
            boolean hasPriorWithin24h = priorTimes.stream()
                    .anyMatch(t -> t.isBefore(interaction.getQueuedAt())
                            && Duration.between(t, interaction.getQueuedAt()).toHours() < 24);
            boolean hasPriorWithin7d = priorTimes.stream()
                    .anyMatch(t -> t.isBefore(interaction.getQueuedAt())
                            && Duration.between(t, interaction.getQueuedAt()).toDays() < 7);
            if (hasPriorWithin24h) {
                recall24h++;
            }
            if (hasPriorWithin7d) {
                recall7d++;
            }
        }

        List<RecallAndDispositionSummary.DispositionCount> topDispositions = cohort.stream()
                .filter(i -> i.getDisposition() != null)
                .collect(Collectors.groupingBy(i -> i.getDisposition().getLabel(), Collectors.counting()))
                .entrySet().stream()
                .map(e -> new RecallAndDispositionSummary.DispositionCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(RecallAndDispositionSummary.DispositionCount::count).reversed())
                .limit(TOP_DISPOSITIONS_LIMIT)
                .toList();

        int total = cohort.size();
        return new RecallAndDispositionSummary(
                total,
                recall24h, ratePct(recall24h, total),
                recall7d, ratePct(recall7d, total),
                topDispositions);
    }

    private BigDecimal ratePct(int count, int total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(count)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
