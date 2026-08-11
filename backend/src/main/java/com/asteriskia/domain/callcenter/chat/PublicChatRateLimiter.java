package com.asteriskia.domain.callcenter.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * PublicChatRateLimiter — limita abuso nos endpoints públicos do widget de chat (Fase 7b),
 * sem exigir usuário autenticado. Em memória (janela deslizante por {@link ConcurrentHashMap}) —
 * não escala pra múltiplas réplicas do backend (exigiria um contador compartilhado, ex: Redis);
 * aceitável hoje porque o backend roda single-instance nesta VPS (produção real de 200 canais
 * vai para servidor dedicado a dimensionar, decisão já registrada no CLAUDE.md).
 */
@Component
public class PublicChatRateLimiter {

    private static final int SESSION_START_LIMIT = 5;
    private static final long SESSION_START_WINDOW_MS = 10 * 60_000L;

    private static final int MESSAGE_LIMIT = 30;
    private static final long MESSAGE_WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, Deque<Long>> sessionStartsByIp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Deque<Long>> messagesBySession = new ConcurrentHashMap<>();

    public boolean allowSessionStart(String clientIp) {
        return allow(sessionStartsByIp, clientIp, SESSION_START_LIMIT, SESSION_START_WINDOW_MS);
    }

    public boolean allowMessage(Long sessionId) {
        return allow(messagesBySession, sessionId, MESSAGE_LIMIT, MESSAGE_WINDOW_MS);
    }

    /** Sincronizado por chave individual (não trava o mapa inteiro). Cada deque fica limitada
     * a no máximo {@code limit} timestamps — memória por chave é sempre pequena e limitada;
     * a chave em si só é removida do mapa se nunca mais for usada (aceito nesta escala: o pior
     * caso é uma deque de poucos `long` por IP único que já passou por aqui uma vez, não um
     * vazamento sem limite por chave). */
    private <K> boolean allow(ConcurrentHashMap<K, Deque<Long>> buckets, K key, int limit, long windowMs) {
        Deque<Long> window = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            long now = System.currentTimeMillis();
            while (!window.isEmpty() && now - window.peekFirst() > windowMs) {
                window.pollFirst();
            }
            boolean allowed = window.size() < limit;
            if (allowed) {
                window.addLast(now);
            }
            return allowed;
        }
    }
}
