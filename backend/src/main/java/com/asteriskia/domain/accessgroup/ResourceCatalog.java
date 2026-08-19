package com.asteriskia.domain.accessgroup;

import java.util.List;

/**
 * Catálogo fixo dos recursos (menus) que podem receber permissão de
 * leitura/escrita por grupo de acesso. Os menus são fixos — só a matriz de
 * permissões é dinâmica — por isso o catálogo vive em código, não em tabela.
 * Manter em sincronia manual com AccessGroups.tsx.
 */
public final class ResourceCatalog {

    private ResourceCatalog() {}

    public static final List<String> TELECOM = List.of(
            "telecom.dashboard",
            "telecom.modulo1",
            "telecom.users"
    );

    public static final List<String> CALLCENTER = List.of(
            "callcenter.agentes",
            "callcenter.ramais",
            "callcenter.filas",
            "callcenter.skills",
            "callcenter.wfm",
            "callcenter.gravacoes",
            "callcenter.desktop",
            "callcenter.copilot",
            "callcenter.supervisao",
            "callcenter.supervisao.redirect",
            "callcenter.fluxos",
            "callcenter.chat",
            "callcenter.kb",
            "callcenter.ia_agentes",
            "callcenter.cobrowsing",
            "callcenter.config"
    );

    public static final List<String> INSIGHTS = List.of(
            "insights.calls",
            "insights.dashboard",
            "insights.processing",
            "insights.scorecards",
            "insights.reports",
            "insights.uploads",
            "insights.semantic_search"
    );

    public static final List<String> FINANCEIRO = List.of(
            "financeiro.ura",
            "financeiro.insights",
            "financeiro.envios",
            "financeiro.callcenter",
            "financeiro.callcenter_nps",
            "financeiro.callcenter_autosservico"
    );

    public static final List<String> GOVERNANCE = List.of(
            "telecom.settings",
            "admin.sso",
            "telecom.audit",
            "telecom.release"
    );

    public static final List<String> AGENTS = List.of();

    public static List<String> all() {
        return java.util.stream.Stream.of(
                        TELECOM.stream(),
                        CALLCENTER.stream(),
                        INSIGHTS.stream(),
                        FINANCEIRO.stream(),
                        GOVERNANCE.stream())
                .flatMap(s -> s)
                .toList();
    }
}

