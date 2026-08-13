package com.asteriskia.domain.callcenter.interaction;

import java.time.LocalDateTime;

/** InteractionView — resposta de leitura de uma interação (tela de Desktop do Agente). */
public record InteractionView(
        Long id,
        String queueName,
        String ani,
        Direction direction,
        LocalDateTime queuedAt,
        LocalDateTime answeredAt,
        LocalDateTime endedAt,
        String dispositionLabel) {

    public static InteractionView from(CcInteraction entity) {
        return new InteractionView(
                entity.getId(),
                entity.getQueue() == null ? null : entity.getQueue().getDisplayName(),
                entity.getAni(),
                entity.getDirection(),
                entity.getQueuedAt(),
                entity.getAnsweredAt(),
                entity.getEndedAt(),
                entity.getDisposition() == null ? null : entity.getDisposition().getLabel());
    }
}
