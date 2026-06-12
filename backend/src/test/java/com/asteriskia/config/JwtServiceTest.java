package com.asteriskia.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * JwtServiceTest — Testa geração, validação e claims dos tokens JWT.
 */
class JwtServiceTest {

    private JwtService jwtService;

    private static final String SECRET =
            "mocksecretmocksecretmocksecretmocksecretmocksecretmocksecret";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretStr", SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationHours", 8);
    }

    @Test
    void generateToken_devolveSujeitoCorreto() {
        String token = jwtService.generateToken("kaio");
        assertThat(jwtService.extractUsername(token)).isEqualTo("kaio");
    }

    @Test
    void generateToken_comExtensao_deveConterExtensao() {
        String token = jwtService.generateToken("kaio", 9001);
        assertThat(token).isNotBlank();
        assertThat(jwtService.extractUsername(token)).isEqualTo("kaio");
    }

    @Test
    void isValid_tokenValido_deveRetornarTrue() {
        String token = jwtService.generateToken("kaio");
        assertThat(jwtService.isValid(token)).isTrue();
    }

    @Test
    void isValid_tokenInvalido_deveRetornarFalse() {
        assertThat(jwtService.isValid("token.invalido.aqui")).isFalse();
    }

    @Test
    void isValid_stringVazia_deveRetornarFalse() {
        assertThat(jwtService.isValid("")).isFalse();
    }

    @Test
    void generateTempToken_deveConterClaimTotpPending() {
        String token = jwtService.generateTempToken("kaio");
        assertThat(jwtService.isTotpPending(token)).isTrue();
        assertThat(jwtService.extractUsername(token)).isEqualTo("kaio");
    }

    @Test
    void isTotpPending_tokenNormal_deveRetornarFalse() {
        String token = jwtService.generateToken("kaio");
        assertThat(jwtService.isTotpPending(token)).isFalse();
    }
}
