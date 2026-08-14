package com.asteriskia.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * SecurityConfig — Segurança JWT + InternalKey da API REST, com RBAC (ADMIN/USER).
 *
 * Endpoints públicos (sem autenticação):
 *   - POST /api/v1/auth/login        → obter token JWT (frontend)
 *   - GET  /api/health               → health check externo (Caddy, monitoração)
 *   - /actuator/health               → health check via Actuator
 *   - /ws/**                         → WebSocket STOMP/SockJS (handshake inicial sem token)
 *
 * Nota sobre /ws: o SockJS faz um GET em /ws/info antes do upgrade WebSocket.
 * Sem liberar /ws/**, o Spring Security retorna 401 nessa requisição e
 * a conexão do Dashboard em tempo real falha. A autenticação de mensagens
 * STOMP (JWT no frame CONNECT) é feita em WebSocketConfig.
 *
 * RBAC: JwtAuthFilter concede ROLE_ADMIN/ROLE_USER (claim "role", legado) e
 * PERM_READ_&lt;resource&gt;/PERM_WRITE_&lt;resource&gt; (claim "perm", grupos de
 * acesso granulares — V22). InternalKeyFilter concede ROLE_INTERNAL para
 * serviços internos (AI Agent).
 *
 * Cada bloco abaixo aceita ROLE_ADMIN OU a permissão granular equivalente —
 * isso é o que permite tokens antigos (só "role", sem "perm") continuarem
 * válidos até expirar/renovar (máx. 8h) durante a transição, e também o que
 * permite um grupo de acesso customizado (ver AccessGroupController) liberar
 * leitura sem escrita, ou vice-versa, por menu.
 *
 * Serviços internos (AI Agent, Scheduler) autenticam via X-Internal-Key header.
 * Frontend e usuários autenticam via Bearer JWT.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final InternalKeyFilter internalKeyFilter;
    private final StreamingTokenFilter streamingTokenFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Achado de bug (Fase 7b): um bean CorsFilter avulso (sem @Order) roda depois
                // do filtro de segurança na cadeia padrão do Spring Boot — o preflight OPTIONS
                // de rota autenticada era barrado com 403 antes de chegar no CorsFilter.
                // http.cors(...) integra o CORS DENTRO da cadeia do Spring Security, que já
                // sabe reconhecer e liberar preflight antes da checagem de autorização.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Públicos — sem token
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/totp/verify", // 2FA: segunda etapa sem JWT
                                "/api/health",          // health check externo (Caddy, Prometheus, monitoração)
                                "/api/v1/ai/chain/active",    // ai-agent consulta chain via X-Internal-Key
                                "/api/v1/ai/providers/*/key-internal", // ai-agent busca keys via X-Internal-Key
                                "/actuator/health",
                                "/ws/**",           // SockJS handshake (GET /ws/info) e upgrade WebSocket
                                // Widget de chat público (Fase 7b) — cliente anônimo, sem JWT de
                                // staff. Autenticação é manual dentro do controller (token de
                                // sessão validado contra o sessionId da URL), não pelo JwtAuthFilter.
                                "/api/v1/callcenter/chat/public/**"
                        ).permitAll()

                        // Leitura de URAs: qualquer usuário autenticado (filtro do dashboard
                        // de chamadas usa a lista de URAs) — precisa vir ANTES da regra de
                        // escrita abaixo, pois a primeira regra que casar decide.
                        .requestMatchers(HttpMethod.GET, "/api/v1/uras/**").authenticated()
                        // Escrita de URAs: ADMIN, serviços internos, ou PERM_WRITE granular.
                        .requestMatchers("/api/v1/uras/**")
                                .hasAnyAuthority("ROLE_ADMIN", "ROLE_INTERNAL", "PERM_WRITE_telecom.modulo1")

                        // Gestão de grupos de acesso — ADMIN puro (evita o ovo-e-galinha de
                        // um grupo customizado precisar de si mesmo pra existir).
                        .requestMatchers("/api/v1/access-groups/**").hasRole("ADMIN")

                        // Leitura de recursos administrativos — ADMIN ou PERM_READ granular.
                        .requestMatchers(HttpMethod.GET, "/api/v1/security/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.security")
                        .requestMatchers(HttpMethod.GET, "/api/v1/settings/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.settings")
                        // AD/LDAP (módulo Call Center, Fase 1) — tela provisória dentro de
                        // Configurações, sem menu próprio ainda (chega na Fase 2). Reusa
                        // "telecom.settings" (mesma decisão do asterisk-config/ai acima).
                        .requestMatchers(HttpMethod.GET, "/api/v1/ad/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.settings")
                        .requestMatchers(HttpMethod.GET, "/api/v1/logs/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.logs")
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.users")
                        .requestMatchers(HttpMethod.GET, "/api/v1/asterisk-config/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.settings")
                        // Configuração de provedores de IA (AISettingsPanel, dentro da aba
                        // Configurações) — reusa "telecom.settings" (mesma página, sem menu
                        // próprio). Achado de segurança: não tinha requestMatcher nenhum e
                        // caía no anyRequest().authenticated() genérico, liberando qualquer
                        // autenticado a sobrescrever API keys de IA e redirecionar STT/LLM/TTS.
                        .requestMatchers(HttpMethod.GET, "/api/v1/ai/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.settings")
                        // Auditoria — só leitura (AuditController não tem endpoint de escrita).
                        // Achado de segurança: catalogado como "telecom.audit" desde a V22 e
                        // exposto como checkbox em AccessGroups.tsx, mas nunca tinha sido
                        // aplicado aqui — caía no anyRequest().authenticated() genérico do fim,
                        // liberando o histórico de auditoria (IPs, mudanças de config, eventos
                        // de login) pra qualquer autenticado, mesmo com o checkbox desmarcado.
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.audit")
                        .requestMatchers(HttpMethod.GET, "/api/v1/numeros-0800/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.0800")
                        .requestMatchers(HttpMethod.GET, "/api/v1/linhas/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.linhas")
                        .requestMatchers(HttpMethod.GET, "/api/v1/operadoras/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.operadoras")
                        // Ranking de Atendimentos — único endpoint de /stats/** que expõe nomes
                        // individuais de cliente (topClients), não só contagens agregadas; os
                        // demais /stats/** seguem no anyRequest().authenticated() genérico abaixo.
                        .requestMatchers(HttpMethod.GET, "/api/v1/stats/calls/ranking")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_telecom.modulo1")
                        // Custos de IA por chamada — movido para o módulo Financeiro
                        // (financeiro.ura); telecom.modulo1 continua protegendo o resto do
                        // Módulo URA (chamadas, ranking, uras).
                        .requestMatchers(HttpMethod.GET, "/api/v1/calls/costs/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_financeiro.ura")
                        // Insights (transcrição/análise de IA de gravações do call center
                        // Verint) — agora SPA independente em /insights; backend continua no
                        // mesmo Spring Boot. Namespace granular por aba (insights.*), espelhando
                        // agents.* — telecom.insights_link (acima) é só o item de menu que abre
                        // a SPA via iframe no Telecom, sem relação com estas permissões de dados.
                        .requestMatchers(HttpMethod.GET, "/api/v1/insights/calls/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_insights.calls")
                        .requestMatchers(HttpMethod.GET, "/api/v1/insights/dashboard/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_insights.dashboard")
                        .requestMatchers(HttpMethod.GET, "/api/v1/insights/processing/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_insights.processing")
                        // Custos de IA (Verint) — movido para o módulo Financeiro
                        // (financeiro.insights); insights.costs foi removido do catálogo (não
                        // protegia mais nada além destas rotas).
                        .requestMatchers(HttpMethod.GET, "/api/v1/insights/costs/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_financeiro.insights")
                        // Fichas de avaliação (scorecards) — Fase 1 da evolução para Quality
                        // Management (V38); mesmo padrão granular das demais abas de Insights.
                        // callcenter.insights.scorecards (Fase 8) é autoridade alternativa de
                        // leitura no MESMO endpoint — a configuração da ficha é global, não há
                        // endpoint próprio de Call Center; escrita continua exclusiva de
                        // insights.scorecards (matcher de baixo).
                        .requestMatchers(HttpMethod.GET, "/api/v1/insights/scorecards/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_insights.scorecards", "PERM_READ_callcenter.insights.scorecards")
                        // Relatórios de performance por atendente — Fase 2 do Quality
                        // Management (V39); posse (supervisor só vê o que ele pediu) é aplicada
                        // no service, não aqui — este matcher só garante a permissão de aba.
                        .requestMatchers(HttpMethod.GET, "/api/v1/insights/reports/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_insights.reports")
                        // Portal do supervisor (upload em lote) — Fase 3 do Quality
                        // Management (V40); posse (supervisor só vê os próprios lotes) é
                        // aplicada no service, não aqui.
                        // Custo de IA dos envios do portal do supervisor (Análise Sob Demanda)
                        // — movido para o módulo Financeiro (financeiro.envios); matcher
                        // específico precisa vir ANTES do genérico de /insights/uploads/**
                        // (insights.uploads), que continua protegendo o resto do portal
                        // (upload/listagem de lotes).
                        .requestMatchers(HttpMethod.GET, "/api/v1/insights/uploads/costs/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_financeiro.envios")
                        .requestMatchers(HttpMethod.GET, "/api/v1/insights/uploads/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_insights.uploads")
                        // Configuração do alerta de gasto de IA por frente (módulo Financeiro).
                        // Path exato por scope (não wildcard) — manter em sincronia manual com
                        // CostAlertService.SCOPES; um scope novo exige replicar os matchers
                        // aqui (GET e escrita), senão a rota cai no anyRequest().authenticated()
                        // genérico do fim, sem exigir financeiro.<scope>.
                        .requestMatchers(HttpMethod.GET, "/api/v1/financeiro/cost-alerts/ura")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_financeiro.ura")
                        .requestMatchers(HttpMethod.GET, "/api/v1/financeiro/cost-alerts/insights")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_financeiro.insights")
                        .requestMatchers(HttpMethod.GET, "/api/v1/financeiro/cost-alerts/envios")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_financeiro.envios")
                        .requestMatchers(HttpMethod.GET, "/api/v1/financeiro/cost-alerts/callcenter")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_financeiro.callcenter")
                        .requestMatchers(HttpMethod.GET, "/api/v1/financeiro/cost-alerts/callcenter_nps")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_financeiro.callcenter_nps")
                        .requestMatchers(HttpMethod.GET, "/api/v1/financeiro/cost-alerts/callcenter_autosservico")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_financeiro.callcenter_autosservico")
                        // Módulo Call Center (voz) — Fase 2. ramal-secret precisa vir ANTES do
                        // matcher genérico de /agentes/**, e é protegido por um resource_key
                        // próprio (callcenter.ramais) por expor a senha SIP do ramal.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/agentes/*/ramal-secret")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.ramais")
                        // Credencial SIP do próprio agente (Fase 13) — callcenter.desktop, não
                        // callcenter.agentes: quem só usa o softphone não gerencia cadastro de
                        // agente. Precisa vir antes do genérico pelo mesmo motivo de ramal-secret.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/agentes/me/sip-credentials")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.desktop")
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/agentes/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.agentes")
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/filas/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.filas")
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/skills/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.skills")
                        // Motivos de pausa e tabulações (Fase 12.6) — catálogos de configuração.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/pause-reasons/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.config")
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/dispositions/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.config")
                        // Ranges de ramal e interruptor global de NPS — Fase 19 (Parte III).
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/settings/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.config")
                        // Pesquisas de satisfação (NPS) — Fase 21 (Parte III).
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/surveys/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.config")
                        // Gravação/retenção/alerta de disco — Fase 3. /api/v1/internal/callcenter/**
                        // (ingestão via dialplan) NÃO entra aqui — fica só sob o InternalKeyFilter,
                        // mesmo padrão de /api/v1/internal/ura-routing.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/recordings/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.gravacoes")
                        // Co-browsing gravado do chat (Fase 17c) — resource próprio, não reusa
                        // callcenter.gravacoes (decisão do plano §6).
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/cobrowsing/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.cobrowsing")
                        // Estado do agente/interação em curso — Fase 4.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/agent-state/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.desktop")
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/interactions/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.desktop")
                        // Painel pessoal do agente: resumo/histórico/pausas do próprio dia — Fase 22
                        // (Parte III). Mesmo resource_key de agent-state/interactions — é "meu
                        // próprio painel", não gestão de outro agente.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/desktop/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.desktop")
                        // Supervisão em tempo real — Fase 6.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/supervision/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.supervisao")
                        // Flow builder visual — Fase 5a. /catalogo é leitura simples, cai no
                        // mesmo matcher (sem regra de escrita própria).
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/fluxos/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.fluxos")
                        // Biblioteca de áudios do Flow Builder — Fase 5c. Ferramenta do próprio
                        // editor de fluxos, reusa o resource callcenter.fluxos (sem tela própria).
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/audios/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.fluxos")
                        // Insights do Call Center — Fase 8. Mesmo pipeline de IA do módulo
                        // Insights (Verint), aplicado às gravações source=callcenter; namespace
                        // granular por aba, espelhando insights.* acima.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/insights/calls/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.insights.calls")
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/insights/dashboard/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.insights.dashboard")
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/insights/processing/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.insights.processing")
                        // Relatórios de performance do Call Center — fonte própria
                        // (agent_performance_reports.source='callcenter', V55); posse
                        // (supervisor só vê o que pediu) aplicada no service, não aqui.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/insights/reports/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.insights.reports")
                        // Canal de chat — Fase 7a (base interna, sem widget público ainda).
                        // /chat/test/** é o simulador de cliente — ROLE_ADMIN puro (dev/QA only,
                        // nunca exposto a cliente real), precisa vir ANTES do matcher genérico
                        // de /chat/** (mesma ordem que ramal-secret já ensina acima).
                        .requestMatchers("/api/v1/callcenter/chat/test/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/chat/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.chat")
                        // Relatório analítico de fila de voz — Fase 9a. /reprocess é ROLE_ADMIN
                        // puro (reprocessamento em massa), precisa vir ANTES do matcher genérico
                        // de leitura de /reports/** (mesma ordem de /chat/test/** acima).
                        .requestMatchers("/api/v1/callcenter/reports/reprocess").hasAuthority("ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/reports/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.reports")
                        // Relatório de qualidade (Fase 26) — reusa a mesma permissão da aba
                        // "Relatórios", path próprio (quality-reports) pra não colidir com o de cima.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/quality-reports/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.reports")
                        // Base de conhecimento (RAG do chat) — Fase 25.
                        .requestMatchers(HttpMethod.GET, "/api/v1/callcenter/kb/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_READ_callcenter.kb")

                        // Escrita nos mesmos recursos — ADMIN ou PERM_WRITE granular.
                        // asterisk-config usa o resource "telecom.settings" (é sub-área da
                        // mesma aba Configurações na UI, sem menu próprio no catálogo).
                        .requestMatchers("/api/v1/security/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.security")
                        .requestMatchers("/api/v1/settings/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.settings")
                        .requestMatchers("/api/v1/ad/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.settings")
                        .requestMatchers("/api/v1/logs/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.logs")
                        .requestMatchers("/api/v1/users/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.users")
                        .requestMatchers("/api/v1/asterisk-config/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.settings")
                        .requestMatchers("/api/v1/ai/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.settings")
                        .requestMatchers("/api/v1/numeros-0800/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.0800")
                        .requestMatchers("/api/v1/linhas/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.linhas")
                        .requestMatchers("/api/v1/operadoras/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.operadoras")
                        .requestMatchers("/api/v1/insights/calls/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_insights.calls")
                        .requestMatchers("/api/v1/insights/dashboard/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_insights.dashboard")
                        .requestMatchers("/api/v1/insights/processing/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_insights.processing")
                        .requestMatchers("/api/v1/insights/costs/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_financeiro.insights")
                        .requestMatchers("/api/v1/insights/scorecards/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_insights.scorecards")
                        .requestMatchers("/api/v1/insights/reports/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_insights.reports")
                        .requestMatchers("/api/v1/insights/uploads/costs/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_financeiro.envios")
                        .requestMatchers("/api/v1/insights/uploads/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_insights.uploads")
                        .requestMatchers("/api/v1/financeiro/cost-alerts/ura")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_financeiro.ura")
                        .requestMatchers("/api/v1/financeiro/cost-alerts/insights")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_financeiro.insights")
                        .requestMatchers("/api/v1/financeiro/cost-alerts/envios")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_financeiro.envios")
                        .requestMatchers("/api/v1/financeiro/cost-alerts/callcenter")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_financeiro.callcenter")
                        .requestMatchers("/api/v1/financeiro/cost-alerts/callcenter_nps")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_financeiro.callcenter_nps")
                        .requestMatchers("/api/v1/financeiro/cost-alerts/callcenter_autosservico")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_financeiro.callcenter_autosservico")
                        // Rotação de secret SIP (Fase 13) — callcenter.ramais, não
                        // callcenter.agentes, mesmo resource de ramal-secret; precisa vir antes
                        // do matcher genérico de /agentes/**.
                        .requestMatchers("/api/v1/callcenter/agentes/*/rotate-secret")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.ramais")
                        .requestMatchers("/api/v1/callcenter/agentes/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.agentes")
                        .requestMatchers("/api/v1/callcenter/filas/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.filas")
                        .requestMatchers("/api/v1/callcenter/skills/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.skills")
                        .requestMatchers("/api/v1/callcenter/pause-reasons/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.config")
                        .requestMatchers("/api/v1/callcenter/dispositions/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.config")
                        .requestMatchers("/api/v1/callcenter/settings/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.config")
                        .requestMatchers("/api/v1/callcenter/surveys/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.config")
                        .requestMatchers("/api/v1/callcenter/recordings/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.gravacoes")
                        // DELETE de eliminação sob demanda (Fase 17c) — GET já coberto acima.
                        .requestMatchers("/api/v1/callcenter/cobrowsing/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.cobrowsing")
                        .requestMatchers("/api/v1/callcenter/agent-state/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.desktop")
                        .requestMatchers("/api/v1/callcenter/interactions/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.desktop")
                        // Redirect de chamada em fila (Fase 15.3) — resource dedicado, pedido
                        // literal de "perfil específico"; precisa vir ANTES do matcher genérico
                        // de /supervision/** (mesma ordem de rotate-secret/chat/test acima).
                        .requestMatchers("/api/v1/callcenter/supervision/redirect/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.supervisao.redirect")
                        .requestMatchers("/api/v1/callcenter/supervision/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.supervisao")
                        .requestMatchers("/api/v1/callcenter/fluxos/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.fluxos")
                        .requestMatchers("/api/v1/callcenter/audios/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.fluxos")
                        .requestMatchers("/api/v1/callcenter/insights/reports/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.insights.reports")
                        .requestMatchers("/api/v1/callcenter/chat/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.chat")
                        .requestMatchers("/api/v1/callcenter/kb/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.kb")
                        .requestMatchers("/api/v1/callcenter/quality-reports/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_callcenter.reports")

                        // Endpoints internos (dialplan/serviços via X-Internal-Key: ura-routing,
                        // ingestão de gravação, chamada de saída — Fase 23) exigem ROLE_INTERNAL
                        // explicitamente, não authenticated() genérico — sem este matcher, um
                        // JWT comum de qualquer usuário do Telecom (mesmo sem nenhuma permissão
                        // de Call Center) já satisfaz authenticated() e conseguia chamar esses
                        // endpoints como se fosse o próprio Asterisk (achado de
                        // ecc:security-reviewer na Fase 23, pré-existente mas fechado aqui).
                        .requestMatchers("/api/v1/internal/**").hasAuthority("ROLE_INTERNAL")

                        // Todos os demais endpoints exigem apenas autenticação (JWT ou InternalKey)
                        .anyRequest().authenticated()
                )
                // InternalKeyFilter roda antes do JWT: serviços internos usam X-Internal-Key
                .addFilterBefore(internalKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // StreamingTokenFilter: só entra em ação nos paths de streaming/download de
                // logs, quando não há Authorization header (SSE não permite header custom).
                .addFilterBefore(streamingTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
