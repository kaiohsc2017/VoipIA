package com.asteriskia.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * InternalKeyFilter — Autenticação de serviços internos via X-Internal-Key.
 *
 * Usado pelo AI Agent Python e pelo Scheduler para se autenticar no backend
 * sem precisar de um token JWT por usuário. A chave é compartilhada via
 * variável de ambiente INTERNAL_API_KEY.
 *
 * Segurança: a chave só trafega na rede Docker interna (sem exposição externa).
 */
@Slf4j
@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Key";

    @Value("${app.internal-api-key:internal_changeme}")
    private String internalApiKey;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String key = request.getHeader(HEADER);

        if (key != null && key.equals(internalApiKey)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            "internal-service",
                            null,
                            List.of()
                    );
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("Requisição autenticada via InternalKey: {}", request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }
}
