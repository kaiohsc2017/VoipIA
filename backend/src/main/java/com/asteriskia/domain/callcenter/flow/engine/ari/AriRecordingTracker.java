package com.asteriskia.domain.callcenter.flow.engine.ari;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * AriRecordingTracker — correlaciona o {@code name} de uma gravação ARI (Stasis record, Fase 21)
 * com quem está esperando o evento {@code RecordingFinished}. Mesmo padrão de
 * {@link AriPlaybackTracker} (Fase 5b) — {@code name} no lugar de {@code playbackId} porque a
 * API de gravação do ARI correlaciona por nome, não por id.
 */
@Component
public class AriRecordingTracker {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<Void> register(String name) {
        var future = new CompletableFuture<Void>();
        pending.put(name, future);
        return future;
    }

    public void complete(String name) {
        var future = pending.remove(name);
        if (future != null) {
            future.complete(null);
        }
    }

    /** Espera o término, sem propagar exceção — timeout/erro (ex.: cliente desistiu, silêncio
     * até o {@code maxDurationSeconds}) apenas encerram a espera silenciosamente; o arquivo, se
     * algo foi gravado, já está no disco de qualquer forma. */
    public void awaitFinished(String name, CompletableFuture<Void> future, Duration timeout) {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            pending.remove(name);
        } catch (InterruptedException e) {
            pending.remove(name);
            Thread.currentThread().interrupt();
        }
    }
}
