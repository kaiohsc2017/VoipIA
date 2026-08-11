package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/**
 * CallTransferEventDto — item do histórico de transferências no detalhe da chamada.
 * targetSwitchCallId (dado técnico do grupo C) só vem preenchido para ADMIN.
 */
public record CallTransferEventDto(
        Integer order,
        LocalDateTime transferredAt,
        String disconnectedBy,
        String targetExtension,
        String targetAgentName,
        boolean resolved,
        String targetSwitchCallId
) {
    public static CallTransferEventDto from(CallTransferEvent event, boolean isAdmin) {
        return new CallTransferEventDto(
                event.getTransferOrder() != null ? event.getTransferOrder().intValue() : null,
                event.getTransferredAt(),
                event.getDisconnectedBy(),
                event.getTargetExtension(),
                event.getTargetAgentName(),
                event.getResolvedAt() != null,
                isAdmin ? event.getTargetSwitchCallId() : null
        );
    }
}
