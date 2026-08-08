package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;

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
}
