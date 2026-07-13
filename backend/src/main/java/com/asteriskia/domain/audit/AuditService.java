package com.asteriskia.domain.audit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * AuditService — Ponto de entrada da auditoria de eventos do sistema (Fase 13).
 *
 * <p>Uso: auditService.log(request, "LOGIN", "Usuário kaio autenticado", true);
 * auditService.log(request, "SETTINGS_CHANGE", "JIRA_BASE_URL alterado", true);
 *
 * <p>O username é lido automaticamente do SecurityContext quando disponível. Para o login, passe o
 * username explicitamente via logAs().
 *
 * <p>IP/User-Agent são extraídos do HttpServletRequest aqui, síncrono, na própria thread da
 * requisição — nunca passar o HttpServletRequest para a escrita assíncrona (AuditWriter): por essa
 * altura a resposta já pode ter sido enviada e o Tomcat reciclado o objeto da requisição.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditWriter writer;
    private static final int MAX_DETAILS_LENGTH = 2000;

    /** Loga ação do usuário autenticado no contexto de segurança atual. */
    public void log(HttpServletRequest request, String action, String details, boolean success) {
        writer.write(
                resolveUsername(),
                resolveIp(request),
                resolveUserAgent(request),
                action,
                truncate(details, MAX_DETAILS_LENGTH),
                success);
    }

    /** Loga ação com username explícito (para login/logout antes de ter contexto). */
    public void logAs(
            HttpServletRequest request,
            String username,
            String action,
            String details,
            boolean success) {
        writer.write(
                username,
                resolveIp(request),
                resolveUserAgent(request),
                action,
                truncate(details, MAX_DETAILS_LENGTH),
                success);
    }

    /** Loga sem request (para jobs agendados ou serviços internos). */
    public void logSystem(String username, String action, String details, boolean success) {
        writer.write(
                username, "system", null, action, truncate(details, MAX_DETAILS_LENGTH), success);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String resolveUsername() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null
                    && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception ex) {
            log.debug("Não foi possível resolver o usuário autenticado atual", ex);
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        if (request == null) return null;
        try {
            // Suporta proxies reversos (Caddy, Nginx)
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) return realIp.trim();
            return request.getRemoteAddr();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveUserAgent(HttpServletRequest request) {
        if (request == null) return null;
        try {
            String ua = request.getHeader("User-Agent");
            return ua != null ? truncate(ua, 512) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
