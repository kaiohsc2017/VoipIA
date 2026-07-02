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
 * RBAC: JwtAuthFilter concede ROLE_ADMIN ou ROLE_USER (claim "role" do JWT).
 * InternalKeyFilter concede ROLE_INTERNAL para serviços internos (AI Agent).
 * Endpoints administrativos (fail2ban, .env, logs de container/AMI, gestão de
 * usuários, edição de config do Asterisk) exigem ROLE_ADMIN. Escrita em URAs
 * (POST/PUT/PATCH/DELETE) exige ADMIN ou INTERNAL — leitura fica liberada
 * para qualquer usuário autenticado (usada no filtro do dashboard de chamadas).
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
                        // Escrita de URAs: apenas ADMIN (frontend) ou serviços internos.
                        .requestMatchers("/api/v1/uras/**").hasAnyRole("ADMIN", "INTERNAL")

                        // Administração pura — apenas ADMIN.
                        .requestMatchers(
                                "/api/v1/security/**",        // controle do fail2ban
                                "/api/v1/settings/**",         // reescreve o .env de produção
                                "/api/v1/logs/**",              // docker logs + AMI
                                "/api/v1/users/**",             // gestão de usuários
                                "/api/v1/asterisk-config/**"    // edita pjsip/extensions + reload AMI
                        ).hasRole("ADMIN")

                        // Todos os demais endpoints exigem apenas autenticação (JWT ou InternalKey)
                        .anyRequest().authenticated()
                )
                // InternalKeyFilter roda antes do JWT: serviços internos usam X-Internal-Key
                .addFilterBefore(internalKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
