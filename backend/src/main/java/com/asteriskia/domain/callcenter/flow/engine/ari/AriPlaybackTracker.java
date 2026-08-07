package com.asteriskia.domain.callcenter.flow.engine.ari;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * AriPlaybackTracker — correlaciona um {@code playbackId} do ARI com quem está esperando o evento
 * {@code PlaybackFinished} (Fase 5b). Compartilhado entre {@link AriClient} (que inicia a espera)
 * e {@link AriEventListener} (que resolve a espera ao receber o evento via WebSocket).
 */
@Component
public class AriPlaybackTracker {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<Void> register(String playbackId) {
        var future = new CompletableFuture<Void>();
        pending.put(playbackId, future);
        return future;
    }

    public void complete(String playbackId) {
        var future = pending.remove(playbackId);
        if (future != null) {
            future.complete(null);
        }
    }

    /** Espera o término, sem propagar exceção — timeout/erro apenas encerram a espera silenciosamente. */
    public void awaitFinished(String playbackId, CompletableFuture<Void> future, Duration timeout) {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            pending.remove(playbackId);
        } catch (InterruptedException e) {
            pending.remove(playbackId);
            Thread.currentThread().interrupt();
        }
    }
}
