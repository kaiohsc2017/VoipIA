package com.asteriskia.domain.callcenter.interaction;

import java.time.LocalDateTime;

/** AgentStateView — resposta de leitura do estado atual do agente. */
public record AgentStateView(
        Long agentId, AgentState state, String pauseReasonLabel, LocalDateTime startedAt) {

    public static AgentStateView from(CcAgentState entity) {
        return new AgentStateView(
                entity.getAgent().getId(),
                entity.getState(),
                entity.getPauseReason() == null ? null : entity.getPauseReason().getLabel(),
                entity.getStartedAt());
    }
}
