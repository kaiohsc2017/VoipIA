package com.asteriskia.config;

import com.asteriskia.domain.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RateLimitFilter — Proteção contra brute-force nos endpoints de login e
 * verificação de código TOTP (Fase 13).
 *
 * Regra: máximo de 10 tentativas por IP em janela deslizante de 60 segundos,
 * contadas por endpoint (login e totp/verify têm buckets independentes).
 * Após o limite, o IP é bloqueado por 5 minutos (retorna 429) naquele endpoint.
 * Controle 100% in-memory (ConcurrentHashMap) sem dependência externa.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    private static final int  MAX_ATTEMPTS = 10;
    private static final long WINDOW_MS    = 60_000L;     // 1 minuto
    private static final long BLOCK_MS     = 5 * 60_000L; // 5 minutos

    /** Endpoints protegidos por rate limit — login e a segunda etapa do 2FA (código TOTP). */
    private static final Set<String> LIMITED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/totp/verify"
    );

    // Chave (IP + path) → contagem + timestamp da primeira tentativa nesta janela.
    // Buckets separados por endpoint evitam que tentativas de login consumam o
    // limite do TOTP (e vice-versa).
    private record Bucket(AtomicInteger count, long windowStart) {}

    private final Map<String, Bucket>  buckets  = new ConcurrentHashMap<>();
    private final Map<String, Long>    blocked  = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getServletPath();
        if (!LIMITED_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }

        String ip  = resolveIp(request);
        String key = ip + "|" + path;
        long now   = Instant.now().toEpochMilli();

        // Verifica bloqueio ativo
        Long blockedUntil = blocked.get(key);
        if (blockedUntil != null) {
            if (now < blockedUntil) {
                long remainSec = (blockedUntil - now) / 1000;
                log.warn("Rate limit ativo: IP {} bloqueado por mais {}s em {}", ip, remainSec, path);
                sendTooMany(response, remainSec);
                return;
            } else {
                blocked.remove(key);
                buckets.remove(key);
            }
        }

        // Controla tentativas na janela corrente
        Bucket bucket = buckets.compute(key, (k, b) -> {
            if (b == null || now - b.windowStart() > WINDOW_MS) {
                return new Bucket(new AtomicInteger(1), now);
            }
            b.count().incrementAndGet();
            return b;
        });

        if (bucket.count().get() > MAX_ATTEMPTS) {
            blocked.put(key, now + BLOCK_MS);
            buckets.remove(key);
            log.warn("Rate limit disparado: IP {} bloqueado por 5 minutos em {}", ip, path);
            auditService.logAs(request, ip, "RATE_LIMIT_BLOCKED",
                    "IP " + ip + " bloqueado após " + MAX_ATTEMPTS + " tentativas em " + path, false);
            sendTooMany(response, BLOCK_MS / 1000);
            return;
        }

        chain.doFilter(req, res);
    }

    private void sendTooMany(HttpServletResponse response, long retryAfterSec) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSec));
        objectMapper.writeValue(response.getWriter(),
                Map.of("error", "Muitas tentativas. Tente novamente em " + retryAfterSec + "s."));
    }

    /**
     * SEGURANÇA: só confia em X-Forwarded-For/X-Real-IP quando a conexão TCP
     * direta vem do próprio Caddy (o único reverse proxy da stack). Sem essa
     * checagem, qualquer container na mesma rede docker (ou um cliente que
     * chegasse direto, se a porta fosse exposta) poderia forjar esses headers
     * e resetar o contador de tentativas de login a cada requisição.
     */
    private String resolveIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) return realIp.trim();
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) return false;
        if ("127.0.0.1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr)) return true;
        for (String host : new String[]{"caddy", "asteriskia-caddy", "voipia-caddy"}) {
            try {
                if (InetAddress.getByName(host).getHostAddress().equals(remoteAddr)) return true;
            } catch (UnknownHostException ignored) {}
        }
        // Subnets privadas de containers Docker (bridge)
        return remoteAddr.startsWith("172.") || remoteAddr.startsWith("10.") || remoteAddr.startsWith("192.168.");
    }
}
