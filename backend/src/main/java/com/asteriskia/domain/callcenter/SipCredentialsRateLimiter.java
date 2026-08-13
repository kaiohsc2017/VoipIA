package com.asteriskia.domain.callcenter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * SipCredentialsRateLimiter — limita quantas vezes por minuto um usuário pode ler a própria
 * credencial SIP ({@code GET /agentes/me/sip-credentials}, Fase 13, D9-A). É uma credencial que
 * circula ao browser sob demanda; auditada a cada leitura, mas ainda vale limitar o ritmo — mesmo
 * padrão em memória de {@code PublicChatRateLimiter} (Fase 7b), aceitável na escala desta VPS
 * single-instance.
 */
@Component
public class SipCredentialsRateLimiter {

    private static final int LIMIT = 10;
    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, Deque<Long>> requestsByUsername = new ConcurrentHashMap<>();

    public boolean allow(String username) {
        Deque<Long> window = requestsByUsername.computeIfAbsent(username, k -> new ArrayDeque<>());
        synchronized (window) {
            long now = System.currentTimeMillis();
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            boolean allowed = window.size() < LIMIT;
            if (allowed) {
                window.addLast(now);
            }
            return allowed;
        }
    }
}
