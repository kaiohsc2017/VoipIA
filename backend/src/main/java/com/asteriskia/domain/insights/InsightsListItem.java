package com.asteriskia.domain.insights;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** InsightsListItem — item da lista de busca, combinando CallAudioFile + resumo de CallInsight/CallEvaluation. */
public record InsightsListItem(
        Long id,
        String callRef,
        LocalDateTime callStarttime,
        Integer durationSeconds,
        String agentName,
        String direction,
        String skill,
        String status,
        String categoriaAssunto,
        String sentimentoGeral,
        String criticidade,
        BigDecimal notaTotal,
        Boolean isFailed,
        // ─── V43 — 6 colunas novas (decisão 7/10 do plano insights-chamadas-campos-xml) ───
        String customerNumber,
        String extension,
        String ani,
        String disconnectedBy,
        Integer numberOfTransfers,
        String transferTargetExtension,
        String transferTargetAgentName
) {
    public static InsightsListItem from(CallAudioFile audioFile, CallInsight insight, CallEvaluation evaluation,
            CallTransferEvent lastTransferEvent) {
        return new InsightsListItem(
                audioFile.getId(),
                audioFile.getCallRef(),
                audioFile.getCallStarttime(),
                audioFile.getDurationSeconds(),
                audioFile.getAgentName(),
                audioFile.getDirection(),
                audioFile.getSkill(),
                audioFile.getStatus(),
                insight != null ? insight.getCategoriaAssunto() : null,
                insight != null ? insight.getSentimentoGeral() : null,
                insight != null ? insight.getCriticidade() : null,
                evaluation != null ? evaluation.getNotaTotal() : null,
                evaluation != null ? evaluation.getIsFailed() : null,
                audioFile.getCustomerNumber(),
                audioFile.getExtension(),
                InsightsAudioFileDto.resolveDisplayAni(audioFile),
                audioFile.getDisconnectedBy(),
                audioFile.getNumberOfTransfers(),
                lastTransferEvent != null ? lastTransferEvent.getTargetExtension() : null,
                lastTransferEvent != null ? lastTransferEvent.getTargetAgentName() : null
        );
    }
}
