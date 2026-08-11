package com.asteriskia.domain.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * TotpServiceTest — Testa geração de segredo, QR Code URL e verificação de códigos TOTP.
 */
class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    @Test
    void generateSecret_deveRetornar16CharsBase32() {
        String secret = totpService.generateSecret();
        assertThat(secret).isNotBlank();
        // Base32 usa apenas letras maiúsculas e dígitos 2-7
        assertThat(secret).matches("[A-Z2-7]+");
        // Padrão: 16 chars (80 bits)
        assertThat(secret.length()).isGreaterThanOrEqualTo(16);
    }

    @Test
    void buildQrCodeUrl_deveConterParametrosCorretos() {
        String secret  = totpService.generateSecret();
        String qrUrl   = totpService.buildQrCodeUrl("kaio", secret);

        // URL do QRCode Server deve conter a URL otpauth encoded
        assertThat(qrUrl).contains("otpauth");
        assertThat(qrUrl).contains("kaio");
    }

    @Test
    void buildOtpAuthUrl_deveConterIssuerEAccount() {
        String secret     = totpService.generateSecret();
        String otpAuthUrl = totpService.buildOtpAuthUrl("kaio", secret);

        assertThat(otpAuthUrl).startsWith("otpauth://totp/");
        assertThat(otpAuthUrl).contains(secret);
    }

    @Test
    void verify_codigoInvalido_deveRetornarFalse() {
        String secret = totpService.generateSecret();
        // Código claramente inválido
        assertThat(totpService.verify(secret, "000000")).isFalse();
        assertThat(totpService.verify(secret, "")).isFalse();
        assertThat(totpService.verify(secret, "abc")).isFalse();
    }

    @Test
    void verify_secreteNulo_naoDeveLancarExcecao() {
        assertThatCode(() -> totpService.verify(null, "123456"))
                .doesNotThrowAnyException();
    }
}
