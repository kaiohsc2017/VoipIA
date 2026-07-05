package com.asteriskia.domain.accessgroup;

import java.util.List;

/**
 * Catálogo fixo dos recursos (menus) que podem receber permissão de
 * leitura/escrita por grupo de acesso. Os menus são fixos — só a matriz de
 * permissões é dinâmica — por isso o catálogo vive em código, não em tabela.
 * Manter em sincronia manual com Sidebar.tsx (telecom.*) e o NAV do
 * agents-platform/frontend/index.html (agents.*).
 */
public final class ResourceCatalog {

    private ResourceCatalog() {}

    public static final List<String> TELECOM = List.of(
            "telecom.dashboard",
            "telecom.modulo1",
            "telecom.modulo2",
            "telecom.modulo3",
            "telecom.agents_link",
            "telecom.datacenter",
            "telecom.masterdata",
            "telecom.users",
            "telecom.settings",
            "telecom.logs",
            "telecom.security",
            "telecom.audit",
            "telecom.docs"
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

    public static List<String> all() {
        return java.util.stream.Stream.concat(TELECOM.stream(), AGENTS.stream()).toList();
    }
}
