package com.asteriskia.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * SecurityConfig — Segurança JWT + InternalKey da API REST.
 *
 * Endpoints públicos (sem autenticação):
 *   - POST /api/v1/auth/login        → obter token JWT (frontend)
 *   - /swagger-ui/**, /api-docs/**   → documentação
 *   - /actuator/health, /prometheus  → monitoração
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
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/api-docs/**",
                                "/actuator/health",
                                "/actuator/prometheus"
                        ).permitAll()
                        // Todos os demais endpoints exigem autenticação (JWT ou InternalKey)
                        .anyRequest().authenticated()
                )
                // InternalKeyFilter roda antes do JWT: serviços internos usam X-Internal-Key
                .addFilterBefore(internalKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
