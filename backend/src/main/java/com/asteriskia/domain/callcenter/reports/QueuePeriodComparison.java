package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;

/** QueuePeriodComparison — dois períodos da mesma fila lado a lado + delta (B - A). */
public record QueuePeriodComparison(
        QueuePeriodMetrics periodA,
        QueuePeriodMetrics periodB,
        int receivedDelta,
        int answeredDelta,
        int abandonedDelta,
        BigDecimal abandonRatePctDelta,
        BigDecimal avgWaitSecondsDelta,
        BigDecimal avgTalkSecondsDelta,
        BigDecimal serviceLevelPctDelta) {
}
