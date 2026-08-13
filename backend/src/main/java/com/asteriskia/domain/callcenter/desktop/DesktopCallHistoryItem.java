package com.asteriskia.domain.callcenter.desktop;

import com.asteriskia.domain.callcenter.interaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DesktopCallHistoryItem — uma linha do histórico do próprio dia do agente (Fase 22).
 *
 * <p>{@code transcriptionStatus} é a regra fechada D21: {@code SEM_GRAVACAO} (nunca gravada ou
 * ainda não ingerida pelo Insights), {@code EM_PROCESSAMENTO} (gravação ingerida, pipeline ainda
 * não concluiu) ou {@code DISPONIVEL} (transcrição pronta, {@code transcript} preenchido). Nenhum
 * outro estado dispara ação — este DTO é somente leitura de artefato já existente.
 */
public record DesktopCallHistoryItem(
        Long interactionId,
        LocalDateTime dateTime,
        Direction direction,
        String ani,
        String queueName,
        Integer talkSeconds,
        BigDecimal npsScore,
        String recordingUrl,
        String transcriptionStatus,
        String transcript) {}
