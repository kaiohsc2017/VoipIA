package com.asteriskia.domain.accessgroup;

import java.util.List;

/**
 * Catálogo fixo dos recursos (menus) que podem receber permissão de
 * leitura/escrita por grupo de acesso. Os menus são fixos — só a matriz de
 * permissões é dinâmica — por isso o catálogo vive em código, não em tabela.
 * Manter em sincronia manual com Sidebar.tsx (telecom.*), o NAV do
 * agents-platform/frontend/index.html (agents.*) e o App.tsx da SPA
 * insights-platform/frontend (insights.*).
 */
public final class ResourceCatalog {

    private ResourceCatalog() {}

    public static final List<String> TELECOM = List.of(
            "telecom.dashboard",
            "telecom.modulo1",
            "telecom.insights_link",
            "telecom.modulo2",
            "telecom.modulo3",
            "telecom.agents_link",
            "telecom.masterdata",
            "telecom.0800",
            "telecom.linhas",
            "telecom.operadoras",
            "telecom.users",
            "telecom.settings",
            "telecom.logs",
            "telecom.security",
            "telecom.audit",
            "telecom.docs",
            "telecom.release"
    );

    public static final List<String> AGENTS = List.of(
            "agents.dashboard",
            "agents.agents",
            "agents.servers",
            "agents.knowledge",
            "agents.logs",
            "agents.reports",
            "agents.secrets",
            "agents.llm"
    );

    // Insights virou SPA independente (/insights, backend Spring Boot reusado)
    // — namespace próprio por aba, espelhando agents.* acima. telecom.insights_link
    // (acima) é só o item de menu no Telecom que abre a SPA via iframe.
    public static final List<String> INSIGHTS = List.of(
            "insights.calls",
            "insights.dashboard",
            "insights.processing",
            "insights.costs"
    );

    public static List<String> all() {
        return java.util.stream.Stream.of(TELECOM.stream(), AGENTS.stream(), INSIGHTS.stream())
                .flatMap(s -> s)
                .toList();
    }
}
