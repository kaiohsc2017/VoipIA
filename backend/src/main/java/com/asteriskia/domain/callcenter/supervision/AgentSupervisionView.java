package com.asteriskia.domain.callcenter.supervision;

import com.asteriskia.domain.callcenter.interaction.AgentState;

/** AgentSupervisionView — estado e produtividade do dia de um agente para o painel de supervisão. */
public record AgentSupervisionView(
        Long agentId,
        String agentName,
        String extension,
        AgentState state,
        String pauseReasonLabel,
        Long secondsInState,
        int answeredToday) {}
