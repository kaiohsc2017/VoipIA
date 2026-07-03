package com.asteriskia.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * StreamingTokenFilter — autentica endpoints SSE (EventSource) via query
 * param "token", restrito a um token de streaming de vida curta (60s,
 * claim scope=stream — ver {@link JwtService#generateStreamingToken}).
 *
 * Só existe porque EventSource não permite enviar header Authorization
 * customizado. Diferente de {@link JwtAuthFilter} (header, qualquer token
 * principal), este filtro: (1) só roda pros paths de streaming/download de
 * logs, (2) rejeita qualquer token sem scope=stream — o JWT principal de 8h
 * colado na URL não seria aceito aqui.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingTokenFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    private static final String[] STREAMING_PATHS = {
        "/api/v1/logs/docker/stream", "/api/v1/logs/asterisk/stream",
        "/api/v1/logs/docker/download", "/api/v1/logs/asterisk/download",
    };

    private boolean isStreamingPath(String uri) {
        for (String p : STREAMING_PATHS) {
            if (uri.equals(p)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String token = request.getParameter("token");

        if (token != null && isStreamingPath(request.getRequestURI())
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                if (jwtService.isValid(token) && jwtService.isStreamingScope(token)) {
                    String username = jwtService.extractUsername(token);
                    String role = jwtService.extractRole(token);
                    Map<String, String> perms = jwtService.extractPermissions(token);

                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                    perms.forEach((resource, flags) -> {
                        if (flags != null && flags.contains("r")) {
                            authorities.add(new SimpleGrantedAuthority("PERM_READ_" + resource));
                        }
                        if (flags != null && flags.contains("w")) {
                            authorities.add(new SimpleGrantedAuthority("PERM_WRITE_" + resource));
                        }
                    });

                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(username, null, authorities);
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                } else {
                    log.debug("Token de streaming inválido/expirado ou sem scope=stream para URI: {}",
                            request.getRequestURI());
                }
            } catch (Exception e) {
                log.debug("Falha ao validar token de streaming: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
