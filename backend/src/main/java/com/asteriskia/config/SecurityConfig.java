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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
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
                                "/ws/**"            // SockJS handshake (GET /ws/info) e upgrade WebSocket
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

                        // Escrita nos mesmos recursos — ADMIN ou PERM_WRITE granular.
                        // asterisk-config usa o resource "telecom.settings" (é sub-área da
                        // mesma aba Configurações na UI, sem menu próprio no catálogo).
                        .requestMatchers("/api/v1/security/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.security")
                        .requestMatchers("/api/v1/settings/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.settings")
                        .requestMatchers("/api/v1/logs/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.logs")
                        .requestMatchers("/api/v1/users/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.users")
                        .requestMatchers("/api/v1/asterisk-config/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.settings")
                        .requestMatchers("/api/v1/ai/**")
                                .hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_telecom.settings")

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
