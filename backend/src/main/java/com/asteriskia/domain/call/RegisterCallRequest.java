package com.asteriskia.domain.call;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;

/** Request do agente Python para registrar a chamada. */
public record RegisterCallRequest(
        @NotBlank String callUuid,
        Integer uraId, // qual URA conduziu a chamada — null = URA legada (id=1)
        Map<String, String> fields,
        String audioFilePath, // caminho do .wav gravado pelo agente Python
        String transcription, // transcrição completa consolidada
        String callerNumber, // número do chamador (CALLERID do Asterisk)
        Integer callDurationSecs, // duração total da chamada em segundos
        String subjectTag, // assunto classificado por IA (best-effort, pode vir null)
        // Consumo de tokens de IA (Fase 1/2 — custo por chamada) — nulos em payloads de
        // ai-agent anteriores a essa feature, tratados como zero (ver AiUsageInfo.empty()).
        @PositiveOrZero Integer sttTokensIn,
        @PositiveOrZero Integer sttTokensOut,
        String sttModel,
        @PositiveOrZero Integer llmTokensIn,
        @PositiveOrZero Integer llmTokensOut,
        String llmModel,
        @PositiveOrZero Integer ttsTokensIn,
        @PositiveOrZero Integer ttsTokensOut,
        String ttsModel) {

    /** Extrai o consumo de tokens desta requisição — nunca null, mesmo se o ai-agent não informar. */
    public AiUsageInfo aiUsage() {
        return new AiUsageInfo(
                sttTokensIn, sttTokensOut, sttModel,
                llmTokensIn, llmTokensOut, llmModel,
                ttsTokensIn, ttsTokensOut, ttsModel);
    }
}
