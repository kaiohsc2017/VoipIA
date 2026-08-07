package com.asteriskia.domain.callcenter.flow.engine;

import java.time.LocalDateTime;

/** FlowExecutionStepView — um passo do traço de uma execução (Fase 5b) — "onde o cliente abandonou". */
public record FlowExecutionStepView(
        Long id, String nodeId, String nodeType, LocalDateTime enteredAt, LocalDateTime exitedAt, String takenEdge, String detail) {

    public static FlowExecutionStepView from(CcFlowExecutionStep step) {
        return new FlowExecutionStepView(
                step.getId(), step.getNodeId(), step.getNodeType(), step.getEnteredAt(), step.getExitedAt(), step.getTakenEdge(), step.getDetail());
    }
}
