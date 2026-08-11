package com.asteriskia.domain.call;

/**
 * Consumo de tokens de IA (STT/LLM/TTS) reportado pelo ai-agent para uma chamada — usado
 * para o rastreamento de custo (URA → aba Custos IA). Campos vêm null quando o ai-agent não
 * conseguiu capturar uso daquela capability (ex: provedor sem suporte a usage_metadata).
 */
public record AiUsageInfo(
        Integer sttTokensIn,
        Integer sttTokensOut,
        String sttModel,
        Integer llmTokensIn,
        Integer llmTokensOut,
        String llmModel,
        Integer ttsTokensIn,
        Integer ttsTokensOut,
        String ttsModel) {

    /** Payload de registro anterior à Fase 1 do ai-agent — nenhum campo de uso informado. */
    public static AiUsageInfo empty() {
        return new AiUsageInfo(0, 0, null, 0, 0, null, 0, 0, null);
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }

    public int sttTokensInOrZero() { return nz(sttTokensIn); }
    public int sttTokensOutOrZero() { return nz(sttTokensOut); }
    public int llmTokensInOrZero() { return nz(llmTokensIn); }
    public int llmTokensOutOrZero() { return nz(llmTokensOut); }
    public int ttsTokensInOrZero() { return nz(ttsTokensIn); }
    public int ttsTokensOutOrZero() { return nz(ttsTokensOut); }
}
