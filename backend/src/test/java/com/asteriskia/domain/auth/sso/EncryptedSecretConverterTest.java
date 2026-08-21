package com.asteriskia.domain.auth.sso;

import static org.junit.jupiter.api.Assertions.*;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EncryptedSecretConverterTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void convertToDatabaseColumn_cifraEDecifraDeVolta() {
        var converter = new EncryptedSecretConverter(randomKey());
        var stored = converter.convertToDatabaseColumn("segredo-do-entra-123");

        assertNotEquals("segredo-do-entra-123", stored);
        assertTrue(stored.startsWith(EncryptedSecretConverter.PREFIX));
        assertEquals("segredo-do-entra-123", converter.convertToEntityAttribute(stored));
    }

    @Test
    void convertToDatabaseColumn_mesmoValorGeraCifraDiferenteACadaVez() {
        var converter = new EncryptedSecretConverter(randomKey());
        var a = converter.convertToDatabaseColumn("mesmo-segredo");
        var b = converter.convertToDatabaseColumn("mesmo-segredo");

        assertNotEquals(a, b); // IV aleatório por cifragem — nunca reaproveita o mesmo texto cifrado
    }

    @Test
    void convertToEntityAttribute_valorLegadoSemPrefixoVoltaComoTextoPuro() {
        var converter = new EncryptedSecretConverter(randomKey());

        assertEquals("segredo-legado-em-texto-puro",
                converter.convertToEntityAttribute("segredo-legado-em-texto-puro"));
    }

    @Test
    void semChaveConfigurada_naoCifraENaoQuebra() {
        var converter = new EncryptedSecretConverter("");

        assertEquals("segredo", converter.convertToDatabaseColumn("segredo"));
        assertEquals("segredo", converter.convertToEntityAttribute("segredo"));
    }

    @Test
    void convertToEntityAttribute_valoresNuloOuVazioPassamDireto() {
        var converter = new EncryptedSecretConverter(randomKey());

        assertNull(converter.convertToDatabaseColumn(null));
        assertEquals("", converter.convertToDatabaseColumn(""));
        assertNull(converter.convertToEntityAttribute(null));
    }
}
