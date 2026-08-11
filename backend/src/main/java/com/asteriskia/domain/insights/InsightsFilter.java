package com.asteriskia.domain.insights;

import java.time.LocalDateTime;

/**
 * InsightsFilter — combinação de filtros opcionais de busca da tela Insights.
 * Qualquer campo nulo/em branco é ignorado na query.
 */
public record InsightsFilter(
        Long id,
        LocalDateTime dateFrom,
        LocalDateTime dateTo,
        String text,
        String phrase,
        String toneCliente,
        String toneAtendente,
        String categoria,
        String criticidade,
        String findingType,
        String agentName,
        String direction,
        String skill,
        Integer durationMin,
        Integer durationMax,
        java.math.BigDecimal notaMin,
        java.math.BigDecimal notaMax,
        Boolean isFailed,
        // ─── V43 — filtros novos (decisão 8 do plano insights-chamadas-campos-xml) ───
        // customerNumber removido (adendo, decisão 11) — não é mais coluna nem filtro, só detalhe.
        String extension,
        String disconnectedBy,
        Boolean hasHold,
        Integer wrapupTimeMin,
        Integer wrapupTimeMax,
        String transferTargetExtension,
        String transferTargetAgentName,
        // ─── V44 — filtros novos (adendo pós-deploy, decisão 11) ───
        String agentLoginId,
        /** Busca em "Tel. Cliente" (ex-ANI) — direction-aware, mesmo critério de
         * InsightsAudioFileDto.resolveDisplayAni (ver InsightsSpecifications). */
        String telCliente,
        // ADMIN-only — ver InsightsController/InsightsQueryService (nunca repassado se !isAdmin)
        String targetSwitchCallId
) {}
