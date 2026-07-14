package com.asteriskia.domain.call;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Uma linha da aba "Custos IA" — chamada + tokens consumidos + custo estimado. */
public record CallCostView(
        Long id,
        LocalDateTime callDate,
        String clientName,
        Integer uraId,
        Integer callDurationSecs,
        Integer sttTokensIn,
        Integer sttTokensOut,
        String sttModel,
        Integer llmTokensIn,
        Integer llmTokensOut,
        String llmModel,
        Integer ttsTokensIn,
        Integer ttsTokensOut,
        String ttsModel,
        int totalTokens,
        BigDecimal estimatedCostUsd) {

    public static CallCostView from(CallRecord r, BigDecimal estimatedCostUsd) {
        int total =
                nz(r.getSttTokensIn()) + nz(r.getSttTokensOut())
                        + nz(r.getLlmTokensIn()) + nz(r.getLlmTokensOut())
                        + nz(r.getTtsTokensIn()) + nz(r.getTtsTokensOut());
        return new CallCostView(
                r.getId(),
                r.getCallDate(),
                r.getClientName(),
                r.getUraId(),
                r.getCallDurationSecs(),
                r.getSttTokensIn(),
                r.getSttTokensOut(),
                r.getSttModel(),
                r.getLlmTokensIn(),
                r.getLlmTokensOut(),
                r.getLlmModel(),
                r.getTtsTokensIn(),
                r.getTtsTokensOut(),
                r.getTtsModel(),
                total,
                estimatedCostUsd);
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }
}
