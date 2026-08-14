package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicChatRateLimiterTest {

    private PublicChatRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new PublicChatRateLimiter();
    }

    @Test
    void allowSessionStart_permiteAteOLimite() {
        String ip = "203.0.113.10";
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.allowSessionStart(ip)).as("tentativa %d", i + 1).isTrue();
        }
    }

    @Test
    void allowSessionStart_bloqueiaAcimaDoLimite() {
        String ip = "203.0.113.11";
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowSessionStart(ip);
        }
        assertThat(rateLimiter.allowSessionStart(ip)).isFalse();
    }

    @Test
    void allowSessionStart_ipsDiferentesNaoInterferemEntreSi() {
        String ip1 = "203.0.113.20";
        String ip2 = "203.0.113.21";
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowSessionStart(ip1);
        }
        assertThat(rateLimiter.allowSessionStart(ip1)).isFalse();
        assertThat(rateLimiter.allowSessionStart(ip2)).isTrue();
    }

    @Test
    void allowMessage_permiteAteOLimite() {
        Long sessionId = 1L;
        for (int i = 0; i < 30; i++) {
            assertThat(rateLimiter.allowMessage(sessionId)).as("mensagem %d", i + 1).isTrue();
        }
    }

    @Test
    void allowMessage_bloqueiaAcimaDoLimite() {
        Long sessionId = 2L;
        for (int i = 0; i < 30; i++) {
            rateLimiter.allowMessage(sessionId);
        }
        assertThat(rateLimiter.allowMessage(sessionId)).isFalse();
    }

    @Test
    void allowMessage_sessoesDiferentesNaoInterferemEntreSi() {
        Long session1 = 3L;
        Long session2 = 4L;
        for (int i = 0; i < 30; i++) {
            rateLimiter.allowMessage(session1);
        }
        assertThat(rateLimiter.allowMessage(session1)).isFalse();
        assertThat(rateLimiter.allowMessage(session2)).isTrue();
    }

    @Test
    void limitesDeSessaoEMensagemSaoIndependentes() {
        // allowSessionStart usa chave String (IP), allowMessage usa chave Long (sessionId) —
        // buckets completamente separados mesmo que os valores "colidissem" como texto.
        for (int i = 0; i < 5; i++) {
            rateLimiter.allowSessionStart("5");
        }
        assertThat(rateLimiter.allowSessionStart("5")).isFalse();
        assertThat(rateLimiter.allowMessage(5L)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void expireStaleBuckets_removeChaveComJanelaExpirada() throws Exception {
        // Fase 10, achado MEDIUM M1: sem o expurgo, esta chave (IP) ficaria para sempre no mapa
        // mesmo com a janela de 10min já vencida há muito tempo.
        rateLimiter.allowSessionStart("203.0.113.99");
        var field = PublicChatRateLimiter.class.getDeclaredField("sessionStartsByIp");
        field.setAccessible(true);
        var buckets = (Map<String, Deque<Long>>) field.get(rateLimiter);
        assertThat(buckets).containsKey("203.0.113.99");
        // Substitui a deque por uma com timestamp bem antigo, simulando janela já expirada —
        // não é possível esperar 10 minutos reais num teste unitário.
        Deque<Long> staleWindow = new ArrayDeque<>();
        staleWindow.add(System.currentTimeMillis() - 20 * 60_000L);
        buckets.put("203.0.113.99", staleWindow);

        rateLimiter.expireStaleBuckets();

        assertThat(buckets).doesNotContainKey("203.0.113.99");
    }

    @Test
    @SuppressWarnings("unchecked")
    void expireStaleBuckets_mantemChaveComJanelaAindaValida() throws Exception {
        rateLimiter.allowSessionStart("203.0.113.98");

        rateLimiter.expireStaleBuckets();

        var field = PublicChatRateLimiter.class.getDeclaredField("sessionStartsByIp");
        field.setAccessible(true);
        var buckets = (Map<String, Deque<Long>>) field.get(rateLimiter);
        assertThat(buckets).containsKey("203.0.113.98");
    }
}
