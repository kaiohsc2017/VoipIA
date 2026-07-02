package com.asteriskia.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JwtService — Geração e validação de tokens JWT (JJWT 0.12+).
 */
@Slf4j
@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secretStr;

    @Value("${app.jwt.expiration-hours:8}")
    private int expirationHours;

    private SecretKey key() {
        byte[] keyBytes = secretStr.getBytes(StandardCharsets.UTF_8);
        // Garante mínimo de 256 bits (32 bytes) para HMAC-SHA256
        byte[] paddedKey = new byte[Math.max(32, keyBytes.length)];
        System.arraycopy(keyBytes, 0, paddedKey, 0, Math.min(keyBytes.length, paddedKey.length));
        return Keys.hmacShaKeyFor(paddedKey);
    }

    /**
     * Gera um token JWT para o usuário informado.
     *
     * @param username  Nome de usuário (subject do token)
     * @return Token JWT assinado
     */
    public String generateToken(String username) {
        return generateToken(username, null);
    }

    /**
     * Gera um token JWT com claim de ramal SIP.
     *
     * @param username  Nome de usuário (subject do token)
     * @param extension Ramal SIP do usuário (ex: 9001)
     * @return Token JWT assinado com claim "extension"
     */
    public String generateToken(String username, Integer extension) {
        return generateToken(username, extension, "USER");
    }

    /**
     * Gera um token JWT com claims de ramal SIP e role (RBAC).
     *
     * @param username  Nome de usuário (subject do token)
     * @param extension Ramal SIP do usuário (ex: 9001)
     * @param role      "ADMIN" ou "USER" — usado por JwtAuthFilter para autorização
     * @return Token JWT assinado com claims "extension" e "role"
     */
    public String generateToken(String username, Integer extension, String role) {
        long nowMs = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(username)
                .issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + (long) expirationHours * 3600 * 1000))
                .claim("role", role != null ? role : "USER")
                .signWith(key(), Jwts.SIG.HS256);
        if (extension != null) {
            builder.claim("extension", extension);
        }
        return builder.compact();
    }


    /**
     * Extrai o username (subject) de um token válido.
     *
     * @param token Token JWT
     * @return username contido no token
     * @throws JwtException se o token for inválido ou expirado
     */
    public String extractUsername(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Extrai a claim "role" de um token válido. Tokens antigos emitidos antes
     * do RBAC (sem a claim) são tratados como "USER" — o menos privilegiado.
     *
     * @param token Token JWT
     * @return "ADMIN" ou "USER"
     */
    public String extractRole(String token) {
        Object role = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role");
        return role != null ? role.toString() : "USER";
    }

    /**
     * Valida o token: assinatura e expiração.
     *
     * @param token Token JWT a validar
     * @return true se válido
     */
    public boolean isValid(String token) {
        try {
            extractUsername(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token JWT inválido: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Gera um token temporário de 5 minutos com claim totp_pending=true.
     * Usado na segunda etapa do login quando 2FA está ativo.
     */
    public String generateTempToken(String username) {
        long nowMs = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + 5 * 60_000L))   // 5 minutos
                .claim("totp_pending", true)
                .signWith(key(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifica se o token tem a claim totp_pending=true.
     */
    public boolean isTotpPending(String token) {
        try {
            Object val = Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .get("totp_pending");
            return Boolean.TRUE.equals(val);
        } catch (Exception e) {
            return false;
        }
    }
}

