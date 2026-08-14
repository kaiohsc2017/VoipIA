package com.asteriskia.domain.callcenter.flow.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fase 10 — rate limit de upload da biblioteca de áudios (6 uploads/min por usuário). */
class AudioUploadRateLimiterTest {

    @Test
    @DisplayName("permite até o limite de uploads por usuário na janela")
    void allow_upToLimit_returnsTrue() {
        var limiter = new AudioUploadRateLimiter();
        for (int i = 0; i < 6; i++) {
            assertThat(limiter.allow("kaio")).isTrue();
        }
    }

    @Test
    @DisplayName("bloqueia a partir do 7º upload na mesma janela")
    void allow_beyondLimit_returnsFalse() {
        var limiter = new AudioUploadRateLimiter();
        for (int i = 0; i < 6; i++) {
            limiter.allow("kaio");
        }
        assertThat(limiter.allow("kaio")).isFalse();
    }

    @Test
    @DisplayName("cada usuário tem a própria janela")
    void allow_independentPerUsername() {
        var limiter = new AudioUploadRateLimiter();
        for (int i = 0; i < 6; i++) {
            limiter.allow("kaio");
        }
        assertThat(limiter.allow("kaio")).isFalse();
        assertThat(limiter.allow("romano")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void expireStaleBuckets_removeUsuarioComJanelaExpirada() throws Exception {
        var limiter = new AudioUploadRateLimiter();
        limiter.allow("kaio");
        var field = AudioUploadRateLimiter.class.getDeclaredField("uploadsByUsername");
        field.setAccessible(true);
        var buckets = (Map<String, Deque<Long>>) field.get(limiter);
        Deque<Long> staleWindow = new ArrayDeque<>();
        staleWindow.add(System.currentTimeMillis() - 5 * 60_000L);
        buckets.put("kaio", staleWindow);

        limiter.expireStaleBuckets();

        assertThat(buckets).doesNotContainKey("kaio");
    }
}
