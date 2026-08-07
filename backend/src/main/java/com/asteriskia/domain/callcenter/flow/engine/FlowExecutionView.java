package com.asteriskia.domain.callcenter.flow.engine;

import java.time.LocalDateTime;

/** FlowExecutionView — projeção de uma execução real do fluxo (Fase 5b), sem os passos (ver {@link FlowExecutionStepView}). */
public record FlowExecutionView(
        Long id,
        Long flowId,
        Long flowVersionId,
        String channelId,
        String channelUniqueId,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        String outcome,
        String lastNodeId) {

    public static FlowExecutionView from(CcFlowExecution execution) {
        return new FlowExecutionView(
                execution.getId(),
                execution.getFlow().getId(),
                execution.getFlowVersion().getId(),
                execution.getChannelId(),
                execution.getChannelUniqueId(),
                execution.getStartedAt(),
                execution.getEndedAt(),
                execution.getOutcome(),
                execution.getLastNodeId());
    }
}
