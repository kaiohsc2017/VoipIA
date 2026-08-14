package com.asteriskia.domain.callcenter.flow.audio;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AudioUploadRateLimiter — limita quantos uploads por minuto um usuário pode enviar à biblioteca
 * de áudios do Flow Builder (Fase 5c). Upload é a operação mais cara em CPU/IO da API autenticada
 * do módulo (dispara um processo {@code ffmpeg}) — mesmo padrão em memória já usado por
 * {@code SipCredentialsRateLimiter}/{@code PublicChatRateLimiter}, aceitável na escala desta VPS
 * single-instance. Fase 10, achado MEDIUM (upload sem rate limit, ausência de teto de frequência).
 */
@Component
public class AudioUploadRateLimiter {

    private static final int LIMIT = 6;
    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, Deque<Long>> uploadsByUsername = new ConcurrentHashMap<>();

    public boolean allow(String username) {
        Deque<Long> window = uploadsByUsername.computeIfAbsent(username, k -> new ArrayDeque<>());
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

    @Scheduled(fixedRate = 15 * 60_000L)
    void expireStaleBuckets() {
        long now = System.currentTimeMillis();
        uploadsByUsername.forEach((key, window) -> {
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                    window.pollFirst();
                }
                if (window.isEmpty()) {
                    uploadsByUsername.remove(key, window);
                }
            }
        });
    }
}
