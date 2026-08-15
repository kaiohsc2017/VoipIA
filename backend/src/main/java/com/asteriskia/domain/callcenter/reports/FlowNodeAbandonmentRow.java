package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;

/**
 * FlowNodeAbandonmentRow — abandono por nó de um fluxo, somado num período (Fase 9c.1).
 */
public record FlowNodeAbandonmentRow(
        String nodeId, String nodeType, int entries, int abandonedHere, BigDecimal abandonRatePct) {
}
