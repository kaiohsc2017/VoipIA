package com.asteriskia.domain.insights;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Uma linha da aba "Custos IA" de Insights — chamada + tokens consumidos + custo estimado.
 * Mirror de CallCostView (domain/call/), sem campos de TTS — o pipeline de Insights só usa
 * STT (transcrição+diarização) e LLM (geração de insights), nunca síntese de voz. */
public record InsightCostView(
        Long id,
        String callRef,
        LocalDateTime callStarttime,
        String agentName,
        Integer durationSeconds,
        Integer sttTokensIn,
        Integer sttTokensOut,
        String sttModel,
        Integer llmTokensIn,
        Integer llmTokensOut,
        String llmModel,
        int totalTokens,
        BigDecimal estimatedCostUsd) {

    public static InsightCostView from(CallAudioFile a, BigDecimal estimatedCostUsd) {
        int total = nz(a.getSttTokensIn()) + nz(a.getSttTokensOut())
                + nz(a.getLlmTokensIn()) + nz(a.getLlmTokensOut());
        return new InsightCostView(
                a.getId(),
                a.getCallRef(),
                a.getCallStarttime(),
                a.getAgentName(),
                a.getDurationSeconds(),
                a.getSttTokensIn(),
                a.getSttTokensOut(),
                a.getSttModel(),
                a.getLlmTokensIn(),
                a.getLlmTokensOut(),
                a.getLlmModel(),
                total,
                estimatedCostUsd);
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }
}
