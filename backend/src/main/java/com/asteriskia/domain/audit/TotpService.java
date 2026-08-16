package com.asteriskia.domain.audit;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * TotpService — Implementação de TOTP (RFC 6238) sem dependências externas (Fase 13).
 *
 * <p>Usa HMAC-SHA1 nativo do Java para gerar/validar códigos de 6 dígitos compatíveis com Google
 * Authenticator, Authy e qualquer app TOTP padrão.
 *
 * <p>Segredo armazenado como Base32 (para compatibilidade com QR Codes padrão TOTP).
 */
@Slf4j
@Service
public class TotpService {

    private static final int DIGITS = 6;
    private static final int STEP_SECS = 30;
    private static final int WINDOW = 1; // aceita ±1 janela (±30s de drift)
    private static final String ALGORITHM = "HmacSHA1";
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    // ─── Geração de Segredo ───────────────────────────────────────────────────

    /** Gera um segredo aleatório de 20 bytes codificado em Base32. */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return toBase32(bytes);
    }

    /**
     * Monta a URL otpauth:// para geração do QR Code. Use um serviço como
     * https://chart.googleapis.com/chart?chs=200x200&cht=qr&chl={url} ou
     * https://api.qrserver.com/v1/create-qr-code/?data={url} para gerar a imagem.
     */
    public String buildOtpAuthUrl(String username, String secret) {
        return String.format(
                "otpauth://totp/VoipIA:%s?secret=%s&issuer=VoipIA&algorithm=SHA1&digits=6&period=30",
                encode(username), encode(secret));
    }

    /**
     * URL para geração de QR Code via API pública (sem necessidade de lib de QR Code). O frontend
     * exibe essa URL como <img src="..."> para o usuário escanear.
     */
    public String buildQrCodeUrl(String username, String secret) {
        String otpUrl = buildOtpAuthUrl(username, secret);
        return "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=" + encode(otpUrl);
    }

    // ─── Validação ────────────────────────────────────────────────────────────

    /**
     * Valida um código TOTP de 6 dígitos. Aceita códigos da janela atual, anterior e posterior
     * (tolerância de ±30s).
     */
    public boolean verify(String secret, String code) {
        if (code == null || code.length() != DIGITS) return false;
        try {
            int provided = Integer.parseInt(code.trim());
            byte[] keyBytes = fromBase32(secret);
            long step = Instant.now().getEpochSecond() / STEP_SECS;

            for (int delta = -WINDOW; delta <= WINDOW; delta++) {
                if (hotp(keyBytes, step + delta) == provided) return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Erro na validação TOTP: {}", e.getMessage());
            return false;
        }
    }

    // ─── HOTP (RFC 4226) ─────────────────────────────────────────────────────

    private int hotp(byte[] key, long counter)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(key, ALGORITHM));

        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(counter);
        byte[] hash = mac.doFinal(buf.array());

        int offset = hash[hash.length - 1] & 0x0F;
        int code =
                ((hash[offset] & 0x7F) << 24)
                        | ((hash[offset + 1] & 0xFF) << 16)
                        | ((hash[offset + 2] & 0xFF) << 8)
                        | (hash[offset + 3] & 0xFF);

        return code % (int) Math.pow(10, DIGITS);
    }

    // ─── Base32 ──────────────────────────────────────────────────────────────

    private String toBase32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32_CHARS.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_CHARS.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    private byte[] fromBase32(String s) {
        s = s.toUpperCase().replaceAll("[^A-Z2-7]", "");
        int bitsLen = s.length() * 5;
        byte[] out = new byte[bitsLen / 8];
        int buffer = 0, bitsLeft = 0, idx = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | BASE32_CHARS.indexOf(c);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[idx++] = (byte) ((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out;
    }

    private String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
