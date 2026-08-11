package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;

/** AgentPeriodComparison — dois períodos do mesmo agente lado a lado + delta (B - A). */
public record AgentPeriodComparison(
        AgentPeriodMetrics periodA,
        AgentPeriodMetrics periodB,
        int answeredDelta,
        BigDecimal avgTalkSecondsDelta,
        long occupiedSecondsDelta,
        long availableSecondsDelta,
        BigDecimal occupancyPctDelta) {
}
