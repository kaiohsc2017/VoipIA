package com.asteriskia.domain.callcenter.supervision;

import java.util.List;

/** SupervisionSnapshot — foto atual do painel de supervisão (filas + agentes). */
public record SupervisionSnapshot(List<QueueSupervisionView> queues, List<AgentSupervisionView> agents) {}
