package com.asteriskia.domain.callcenter.desktop;

import java.time.LocalDateTime;

/** DesktopPauseItem — uma pausa do próprio dia do agente, já recortada para dentro da janela do
 * dia (Fase 22). {@code endedAt} nulo significa pausa ainda em curso. */
public record DesktopPauseItem(
        String reasonLabel, LocalDateTime startedAt, LocalDateTime endedAt, long durationSeconds) {}
