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
            "telecom.callcenter_link",
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
    // "insights.costs" foi removido daqui (V41) — as rotas /insights/costs/** que ele
    // protegia migraram para "financeiro.insights" (módulo Financeiro); não sobrou nada
    // mais pra esse resource_key proteger.
    public static final List<String> INSIGHTS = List.of(
            "insights.calls",
            "insights.dashboard",
            "insights.processing",
            "insights.scorecards",
            "insights.reports",
            "insights.uploads"
    );

    // Módulo Financeiro — centraliza as 2 telas de custo de IA (lista + dashboard) de cada
    // frente de uso, antes espalhadas em telecom.modulo1 (URA), insights.costs (Insights,
    // removido acima) e insights.uploads (Análise Sob Demanda). telecom.modulo1 e
    // insights.uploads continuam existindo — protegem o restante de suas telas — só
    // perderam a responsabilidade sobre as rotas /costs/**.
    public static final List<String> FINANCEIRO = List.of(
            "financeiro.ura",
            "financeiro.insights",
            "financeiro.envios",
            "financeiro.callcenter",
            "financeiro.callcenter_nps",
            // callcenter_autosservico (Fase 25, §25.4): custo de IA do nó consultar_base do chat
            // (embedding é local/CPU, sem custo — só a geração final via Gemini gera gasto). Sem
            // tela própria no submenu Financeiro do Telecom — mesmo padrão de callcenter_nps, o
            // painel de alerta vive embutido na aba "Base de Conhecimento" do Call Center.
            "financeiro.callcenter_autosservico"
    );

    // Módulo Call Center (voz) — Fase 2 do plano modulo-callcenter-omnicanal.plan.md.
    // callcenter.ramais protege só a rota sensível de senha do ramal
    // (GET /callcenter/agentes/{id}/ramal-secret); o CRUD de agente em si usa callcenter.agentes.
    // callcenter.insights.* (Fase 8) espelha as 5 abas de insights.* acima — Chamadas,
    // Dashboard, Processamento, Fichas de Qualidade e Relatórios — aplicadas às gravações
    // source=callcenter em vez de source=verint. callcenter.insights.scorecards é
    // somente-leitura (a configuração da ficha continua global, reusa GET /insights/scorecards
    // com esta permissão como autoridade alternativa — o Call Center nunca escreve a
    // configuração). callcenter.insights.reports usa fonte própria (agent_performance_reports.
    // source, V55) para nunca misturar agregados de verint e callcenter sob o mesmo agentName.
    public static final List<String> CALLCENTER = List.of(
            "callcenter.agentes",
            "callcenter.ramais",
            "callcenter.filas",
            "callcenter.skills",
            "callcenter.gravacoes",
            "callcenter.desktop",
            "callcenter.supervisao",
            // callcenter.supervisao.redirect (Fase 15.3): ação de "perfil específico" — retirar
            // chamada da fila e redirecionar. Ação sensível dentro da tela de Supervisão, não um
            // menu próprio — mesmo padrão de callcenter.ramais (senha do ramal).
            "callcenter.supervisao.redirect",
            "callcenter.fluxos",
            "callcenter.insights.calls",
            "callcenter.insights.dashboard",
            "callcenter.insights.processing",
            "callcenter.insights.scorecards",
            "callcenter.insights.reports",
            // callcenter.chat (Fase 7a — base interna): fila/conversas/respostas rápidas do
            // agente. Não cobre /callcenter/chat/test/** (simulador de cliente, ROLE_ADMIN puro,
            // sem resource_key — nunca deve virar algo que um grupo customizado possa conceder).
            "callcenter.chat",
            // callcenter.reports (Fase 9a): relatório analítico de fila de voz. Distinto de
            // callcenter.insights.reports (Fase 8, relatório de performance por atendente) —
            // fontes de dado e granularidade diferentes. Não cobre POST /reports/reprocess
            // (ROLE_ADMIN puro, sem resource_key — reprocessamento em massa não é ação de rotina
            // de um grupo customizado).
            "callcenter.reports",
            // callcenter.config (Fase 12.6): CRUD de motivos de pausa e tabulações — catálogos
            // de configuração do Call Center que antes só existiam via seed (V47), sem UI própria.
            "callcenter.config",
            // callcenter.kb (Fase 25): base de conhecimento própria (artigos + fontes externas)
            // consultada pelo nó consultar_base do chat.
            "callcenter.kb",
            // callcenter.cobrowsing (Fase 17): co-browsing gravado do chat. Registrado aqui já
            // na sub-fase 17a (só consentimento, sem player/retenção ainda) para os endpoints
            // administrativos das sub-fases 17c/17d nascerem sem precisar de outra migration de
            // RBAC — nasce só com ADMIN (sem tela própria nem matcher em SecurityConfig ainda,
            // não reusa callcenter.gravacoes de propósito, ver o plano §6).
            "callcenter.cobrowsing"
    );

    public static List<String> all() {
        return java.util.stream.Stream.of(
                        TELECOM.stream(),
                        AGENTS.stream(),
                        INSIGHTS.stream(),
                        FINANCEIRO.stream(),
                        CALLCENTER.stream())
                .flatMap(s -> s)
                .toList();
    }
}
