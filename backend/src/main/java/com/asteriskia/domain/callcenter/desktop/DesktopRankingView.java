package com.asteriskia.domain.callcenter.desktop;

import java.math.BigDecimal;
import java.util.List;

/**
 * DesktopRankingView — posição do próprio agente no ranking (D3: própria posição + topo anonimizado).
 */
public record DesktopRankingView(
        Integer position,
        Integer totalAgents,
        BigDecimal npsScore,
        String tierLabel,
        List<AnonymousRankingItem> top3Anonymous) {

    public record AnonymousRankingItem(Integer position, BigDecimal npsScore, String label) {}
}
