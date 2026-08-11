package com.asteriskia.domain.callcenter.interaction;

import jakarta.validation.constraints.NotNull;

/**
 * AgentStateRequest — payload de transição manual de estado do agente. {@code pauseReasonId} só
 * é aceito (e exigido) quando {@code state == PAUSA}; ver validação em
 * {@link CallCenterAgentStateService#setState}.
 */
public record AgentStateRequest(@NotNull AgentState state, Long pauseReasonId) {}
