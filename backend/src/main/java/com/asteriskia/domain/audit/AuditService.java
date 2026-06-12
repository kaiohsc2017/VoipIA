package com.asteriskia.domain.audit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * AuditService — Gravação assíncrona de eventos de auditoria (Fase 13).
 *
 * Uso:
 *   auditService.log(request, "LOGIN", "Usuário kaio autenticado", true);
 *   auditService.log(request, "SETTINGS_CHANGE", "JIRA_BASE_URL alterado", true);
 *
 * O username é lido automaticamente do SecurityContext quando disponível.
 * Para o login, passe o username explicitamente via logAs().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repo;

    /** Loga ação do usuário autenticado no contexto de segurança atual. */
    @Async
    public void log(HttpServletRequest request, String action, String details, boolean success) {
        String username = resolveUsername();
        persist(username, resolveIp(request), resolveUserAgent(request), action, details, success);
    }

    /** Loga ação com username explícito (para login/logout antes de ter contexto). */
    @Async
    public void logAs(HttpServletRequest request, String username, String action, String details, boolean success) {
        persist(username, resolveIp(request), resolveUserAgent(request), action, details, success);
    }

    /** Loga sem request (para jobs agendados ou serviços internos). */
    @Async
    public void logSystem(String username, String action, String details, boolean success) {
        persist(username, "system", null, action, details, success);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void persist(String username, String ip, String userAgent,
                         String action, String details, boolean success) {
        try {
            AuditLog entry = AuditLog.builder()
                    .username(username)
                    .ipAddress(ip)
                    .userAgent(userAgent)
                    .action(action)
                    .details(truncate(details, 2000))
                    .success(success)
                    .build();
            repo.save(entry);
        } catch (Exception e) {
            // Auditoria nunca deve derrubar a requisição principal
            log.error("Erro ao gravar audit log [action={}]: {}", action, e.getMessage());
        }
    }

    private String resolveUsername() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        if (request == null) return null;
        // Suporta proxies reversos (Caddy, Nginx)
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }

    private String resolveUserAgent(HttpServletRequest request) {
        if (request == null) return null;
        String ua = request.getHeader("User-Agent");
        return ua != null ? truncate(ua, 512) : null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
