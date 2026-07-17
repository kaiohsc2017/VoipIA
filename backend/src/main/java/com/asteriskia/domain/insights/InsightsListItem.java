package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/** InsightsListItem — item da lista de busca, combinando CallAudioFile + resumo de CallInsight. */
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
        String criticidade
) {
    public static InsightsListItem from(CallAudioFile audioFile, CallInsight insight) {
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
                insight != null ? insight.getCriticidade() : null
        );
    }
}
