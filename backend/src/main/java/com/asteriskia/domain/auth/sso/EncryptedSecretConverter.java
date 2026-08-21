package com.asteriskia.domain.auth.sso;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra/decifra o {@code client_secret} do SSO em repouso (AES-256-GCM) — a coluna
 * gravava o segredo do App Registration do Entra ID em texto puro, expondo a credencial
 * viva em qualquer dump/backup/réplica do Postgres.
 *
 * Compatibilidade retroativa: valores já persistidos sem o prefixo {@link #PREFIX} são
 * tratados como legado em texto puro — lidos como estão, e re-cifrados automaticamente no
 * próximo {@code save()} (ver {@link SsoSecretReencryptionRunner}, que força esse save uma
 * vez no boot). Se {@code SSO_SECRET_ENCRYPTION_KEY} não estiver configurada, a cifragem é
 * ignorada (nunca derruba o boot) — mas o achado de segurança só é fechado de fato depois
 * que a variável for definida em produção.
 */
@Converter
@Component
public class EncryptedSecretConverter implements AttributeConverter<String, String> {

    static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final byte[] key;

    public EncryptedSecretConverter(@Value("${app.sso.secret-encryption-key:}") String base64Key) {
        this.key = base64Key.isBlank() ? null : Base64.getDecoder().decode(base64Key);
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        if (plain == null || plain.isBlank() || key == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao cifrar segredo do SSO", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || stored.isBlank() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException(
                    "SSO_SECRET_ENCRYPTION_KEY não configurada, mas há um segredo já cifrado no banco.");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao decifrar segredo do SSO", e);
        }
    }
}
