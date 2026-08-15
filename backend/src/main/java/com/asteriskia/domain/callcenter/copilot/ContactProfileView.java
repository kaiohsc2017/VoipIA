package com.asteriskia.domain.callcenter.copilot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ContactProfileView — resposta de leitura do perfil de IA (tela de Desktop do Agente / Chat,
 * Fase 16.3). {@code status}:
 * <ul>
 *   <li>{@code UNAVAILABLE} — a interação não tem contato identificado (Fase 14); nada a gerar.</li>
 *   <li>{@code GENERATING} — geração assíncrona em andamento, sem nenhum perfil ainda; o
 *       chamador deve consultar de novo em alguns segundos (polling).</li>
 *   <li>{@code READY} — {@code profile} preenchido; pode já estar sendo regerado em segundo
 *       plano se estiver fora da janela de cache, mas o atendimento nunca espera por isso.</li>
 * </ul>
 */
public record ContactProfileView(
        String status,
        Long profileId,
        String resumoPerfil,
        String sentimentoHistorico,
        List<String> temasRecorrentes,
        BigDecimal riscoEscalonamento,
        List<ContactProfileContent.AcaoSugerida> acoesSugeridas,
        LocalDateTime generatedAt,
        String model,
        BigDecimal costUsd) {

    public static ContactProfileView unavailable() {
        return new ContactProfileView("UNAVAILABLE", null, null, null, null, null, null, null, null, null);
    }

    public static ContactProfileView generating() {
        return new ContactProfileView("GENERATING", null, null, null, null, null, null, null, null, null);
    }

    public static ContactProfileView ready(CcContactProfile profile, ContactProfileContent content) {
        return new ContactProfileView(
                "READY",
                profile.getId(),
                content.resumoPerfil(),
                content.sentimentoHistorico(),
                content.temasRecorrentes(),
                content.riscoEscalonamento(),
                content.acoesSugeridas(),
                profile.getGeneratedAt(),
                profile.getModel(),
                profile.getCostUsd());
    }
}
