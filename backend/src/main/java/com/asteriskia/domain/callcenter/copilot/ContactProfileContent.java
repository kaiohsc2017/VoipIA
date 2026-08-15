package com.asteriskia.domain.callcenter.copilot;

import java.math.BigDecimal;
import java.util.List;

/**
 * ContactProfileContent — conteúdo estruturado do perfil gerado por IA (Fase 16.2), exatamente o
 * schema pedido ao Gemini via {@code responseSchema} (mesmo padrão de {@code
 * CallCenterNpsTranscriptionScheduler}). {@code riscoEscalonamento} é sempre clampado para
 * {@code [0, 1]} antes de persistir — nunca o valor cru do modelo (lição do overflow numérico da
 * Fase 8, {@code call_insights.aderencia_script}).
 */
public record ContactProfileContent(
        String resumoPerfil,
        String sentimentoHistorico,
        List<String> temasRecorrentes,
        BigDecimal riscoEscalonamento,
        List<AcaoSugerida> acoesSugeridas) {

    public record AcaoSugerida(String acao, String justificativa) {}
}
