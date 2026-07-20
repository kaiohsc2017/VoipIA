package com.asteriskia.domain.insights;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * EvaluationService — cálculo determinístico da avaliação de qualidade de uma chamada
 * contra a ficha (scorecard) ativa no momento do processamento.
 *
 * Princípio central (mesma lição do clamp de {@code aderencia_script}, bug real de
 * overflow em produção — ver V35/V38): o LLM só produz texto (justificativa, trecho de
 * referência); nota por item, nota total ponderada e a regra de auto-fail são sempre
 * calculadas aqui, nunca aceitas prontas do modelo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final QualityScorecardRepository scorecardRepository;
    private final ScorecardItemRepository scorecardItemRepository;
    private final CallEvaluationRepository evaluationRepository;
    private final CallEvaluationItemRepository evaluationItemRepository;

    public record EvaluatedItem(Long itemId, Double nota, String justificativa, String trechoReferencia) {}

    /**
     * Persiste a avaliação de uma chamada contra a ficha informada. Substitui por completo
     * uma avaliação anterior da mesma chamada (mesmo padrão de upsert do restante da
     * ingestão de insights — reprocessamento nunca acontece em paralelo consigo mesmo).
     */
    @Transactional
    public CallEvaluation evaluate(Long audioFileId, Long scorecardId, List<EvaluatedItem> items,
                                    Integer llmTokensIn, Integer llmTokensOut, String llmModel) {
        QualityScorecard scorecard = scorecardRepository.findById(scorecardId)
                .orElseThrow(() -> new IllegalArgumentException("Ficha de avaliação não encontrada: id=" + scorecardId));
        List<ScorecardItem> scorecardItems = scorecardItemRepository.findByScorecardIdOrderByOrdemAsc(scorecardId);
        Map<Long, ScorecardItem> itemsById = scorecardItems.stream()
                .collect(java.util.stream.Collectors.toMap(ScorecardItem::getId, i -> i));

        BigDecimal pesoTotal = BigDecimal.ZERO;
        BigDecimal somaPonderada = BigDecimal.ZERO;
        boolean isFailed = false;
        String failReason = null;

        List<CallEvaluationItem> clampedItems = new java.util.ArrayList<>();
        for (EvaluatedItem raw : items) {
            ScorecardItem item = itemsById.get(raw.itemId());
            if (item == null) {
                log.warn("Item de ficha inexistente na avaliação (itemId={}) — ignorado", raw.itemId());
                continue;
            }
            BigDecimal notaClamped = clamp(raw.nota(), item.getNotaMaxima());
            clampedItems.add(CallEvaluationItem.builder()
                    .itemId(item.getId())
                    .nota(notaClamped)
                    .justificativa(raw.justificativa())
                    .trechoReferencia(raw.trechoReferencia())
                    .build());

            BigDecimal notaMaximaBd = BigDecimal.valueOf(item.getNotaMaxima());
            BigDecimal fracao = notaMaximaBd.compareTo(BigDecimal.ZERO) > 0
                    ? notaClamped.divide(notaMaximaBd, 6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            somaPonderada = somaPonderada.add(fracao.multiply(item.getPeso()));
            pesoTotal = pesoTotal.add(item.getPeso());

            if (Boolean.TRUE.equals(item.getIsCritical()) && notaClamped.compareTo(BigDecimal.ZERO) == 0) {
                isFailed = true;
                failReason = "Item crítico reprovado: " + item.getPergunta();
            }
        }

        BigDecimal notaTotal = pesoTotal.compareTo(BigDecimal.ZERO) > 0
                ? somaPonderada.divide(pesoTotal, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        evaluationRepository.findByAudioFileId(audioFileId)
                .ifPresent(existing -> evaluationRepository.delete(existing));

        CallEvaluation evaluation = evaluationRepository.save(CallEvaluation.builder()
                .audioFileId(audioFileId)
                .scorecardId(scorecard.getId())
                .notaTotal(notaTotal)
                .isFailed(isFailed)
                .failReason(failReason)
                .llmTokensIn(llmTokensIn != null ? llmTokensIn : 0)
                .llmTokensOut(llmTokensOut != null ? llmTokensOut : 0)
                .llmModel(llmModel)
                .build());

        Long evaluationId = evaluation.getId();
        clampedItems.forEach(i -> i.setEvaluationId(evaluationId));
        evaluationItemRepository.saveAll(clampedItems);

        log.info("Avaliação persistida para audioFileId={} (scorecardId={}, nota={}, isFailed={})",
                audioFileId, scorecardId, notaTotal, isFailed);

        return evaluation;
    }

    private BigDecimal clamp(Double rawNota, Integer notaMaxima) {
        double nota = rawNota != null ? rawNota : 0.0;
        double max = notaMaxima != null ? notaMaxima : 10;
        double clamped = Math.max(0.0, Math.min(max, nota));
        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP);
    }
}
