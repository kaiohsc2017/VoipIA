package com.asteriskia.domain.callcenter.quality;

/** QualityReportScopeType — dimensão de recorte do relatório de qualidade (Fase 26). AGENT/QUEUE
 * usam {@code scopeValue} (nome do agente/fila); GERAL cobre toda a operação (scopeValue nulo). */
public enum QualityReportScopeType {
    AGENT,
    QUEUE,
    GERAL
}
