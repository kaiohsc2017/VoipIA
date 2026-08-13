package com.asteriskia.domain.callcenter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fase 13, D9-A — rate limit de leitura da credencial SIP (10 req/min por usuário). */
class SipCredentialsRateLimiterTest {

    @Test
    @DisplayName("permite até o limite de requisições por usuário na janela")
    void allow_upToLimit_returnsTrue() {
        var limiter = new SipCredentialsRateLimiter();

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.allow("kaio")).isTrue();
        }
    }

    @Test
    @DisplayName("bloqueia a partir da 11ª requisição na mesma janela")
    void allow_beyondLimit_returnsFalse() {
        var limiter = new SipCredentialsRateLimiter();

        for (int i = 0; i < 10; i++) {
            limiter.allow("kaio");
        }

        assertThat(limiter.allow("kaio")).isFalse();
    }

    @Test
    @DisplayName("cada usuário tem a própria janela — um bloqueado não afeta o outro")
    void allow_independentPerUsername() {
        var limiter = new SipCredentialsRateLimiter();

        for (int i = 0; i < 10; i++) {
            limiter.allow("kaio");
        }
        assertThat(limiter.allow("kaio")).isFalse();

        assertThat(limiter.allow("romano")).isTrue();
    }
}
