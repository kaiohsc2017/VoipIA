package com.asteriskia.domain.callcenter.desktop;

import java.math.BigDecimal;
import java.util.List;

/**
 * DesktopQualityView — resumo de avaliações de qualidade recebidas pelo agente.
 */
public record DesktopQualityView(
        Integer totalEvaluations,
        BigDecimal avgScore,
        List<String> strongPoints,
        List<String> improvementPoints) {}
