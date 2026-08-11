package com.asteriskia.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return generateToken(username, extension, role, Map.of());
    }

    /**
     * Gera um token JWT com claims de ramal, role (legado) e a matriz de
     * permissões granular do grupo de acesso (RBAC — V22).
     *
     * @param username  Nome de usuário (subject do token)
     * @param extension Ramal SIP do usuário (ex: 9001)
     * @param role      "ADMIN" ou "USER" — mantido em paralelo (dual-emit) para
     *                  compatibilidade retroativa durante a transição para
     *                  grupos de acesso; ver {@code com.asteriskia.domain.accessgroup}.
     * @param perms     Matriz {resource_key: "r"|"w"|"rw"} resolvida do grupo
     *                  do usuário (AccessGroupService.permissionsFor) — vazia
     *                  omite a claim "perm" do token.
     * @return Token JWT assinado com claims "extension", "role" e "perm"
     */
    public String generateToken(String username, Integer extension, String role, Map<String, String> perms) {
        return generateToken(username, extension, role, perms, Set.of());
    }

    /**
     * Gera um token JWT com claims de ramal, role, permissões granulares e as
     * BUs (Unidades de Negócio) do usuário — usadas para restringir os dados
     * visíveis a essas BUs (controle de acesso por BU). ADMIN não carrega
     * claim "bu" — enxerga todas as BUs, ver {@code BusinessUnitContext}.
     *
     * @param businessUnitIds IDs das BUs do usuário; vazio omite a claim "bu"
     */
    public String generateToken(String username, Integer extension, String role, Map<String, String> perms,
                                 Collection<Integer> businessUnitIds) {
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
        if (perms != null && !perms.isEmpty()) {
            builder.claim("perm", perms);
        }
        if (businessUnitIds != null && !businessUnitIds.isEmpty()) {
            builder.claim("bu", List.copyOf(businessUnitIds));
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
     * Extrai a claim "perm" (matriz de permissões granular) de um token
     * válido. Tokens antigos emitidos antes do RBAC granular (sem a claim)
     * retornam mapa vazio — quem chama deve então cair no fallback de ROLE_
     * legado (ver JwtAuthFilter).
     *
     * @param token Token JWT
     * @return mapa {resource_key: "r"|"w"|"rw"}, vazio se ausente
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> extractPermissions(String token) {
        Object perm = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("perm");
        return perm instanceof Map ? (Map<String, String>) perm : Map.of();
    }

    /**
     * Extrai a claim "bu" (IDs das Unidades de Negócio do usuário) de um
     * token válido. Ausente para ADMIN (bypassa o filtro) ou tokens antigos
     * emitidos antes do controle de acesso por BU — quem chama deve tratar
     * lista vazia como "sem BUs atribuídas", não como "todas as BUs".
     *
     * @param token Token JWT
     * @return lista de IDs de BU, vazia se ausente
     */
    @SuppressWarnings("unchecked")
    public List<Integer> extractBusinessUnitIds(String token) {
        Object bu = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("bu");
        if (!(bu instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(v -> v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString()))
                .toList();
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

    /** TTL do token de streaming — só o suficiente pra abrir a conexão WS/SSE. */
    private static final long STREAMING_TOKEN_TTL_MS = 60_000L;

    /**
     * Gera um token de streaming de vida curta (60s), usado em WebSocket/SSE
     * onde o browser não permite header Authorization customizado (só cookie
     * ou query string). Achado de segurança (débito aceito): o JWT principal
     * (8h de validade) trafegando na URL ficava exposto em logs de acesso,
     * histórico do browser e proxies — este token carrega as mesmas claims
     * "role"/"perm" do usuário (pra manter a autorização granular idêntica),
     * mas expira em 60s e tem a claim "scope=stream" que o distingue do
     * token principal — {@link #isStreamingScope} rejeita qualquer token sem
     * essa claim, então o token principal não pode ser usado nesses endpoints
     * mesmo que alguém tente colar ele na URL manualmente.
     */
    public String generateStreamingToken(String username, String role, Map<String, String> perms) {
        return generateStreamingToken(username, role, perms, List.of());
    }

    /** Variante que também propaga a claim "bu" — mantém o mesmo escopo de BU do token principal. */
    public String generateStreamingToken(String username, String role, Map<String, String> perms,
                                          Collection<Integer> businessUnitIds) {
        long nowMs = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(username)
                .issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + STREAMING_TOKEN_TTL_MS))
                .claim("role", role != null ? role : "USER")
                .claim("scope", "stream")
                .signWith(key(), Jwts.SIG.HS256);
        if (perms != null && !perms.isEmpty()) {
            builder.claim("perm", perms);
        }
        if (businessUnitIds != null && !businessUnitIds.isEmpty()) {
            builder.claim("bu", List.copyOf(businessUnitIds));
        }
        return builder.compact();
    }

    /**
     * Verifica se o token tem a claim scope=stream — usado por
     * {@code StreamingTokenFilter} pra garantir que só um token de streaming
     * (nunca o principal de 8h) seja aceito via query string.
     */
    public boolean isStreamingScope(String token) {
        try {
            Object scope = Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .get("scope");
            return "stream".equals(scope);
        } catch (Exception e) {
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

    /** TTL do token de cliente do chat público (Fase 7b) — cobre a duração de uma conversa
     * inteira (não só a abertura de uma conexão, como o token de streaming de 60s acima). */
    private static final long CHAT_CUSTOMER_TOKEN_TTL_MS = 2 * 3600 * 1000L;

    /**
     * Gera um token de vida curta (2h) para o cliente anônimo do widget de chat público
     * (Fase 7b) — nunca carrega "role"/"perm"/"bu": este token não é de staff e não deve
     * nunca ganhar autoridade RBAC, só autoriza ações na sessão de chat indicada. A claim
     * "scope=chat_customer" o distingue do token principal (8h) e do de streaming (60s,
     * scope=stream) — {@link #validateChatCustomerToken} rejeita qualquer token sem essa
     * claim exata, então nenhum dos outros dois pode ser usado nos endpoints públicos de chat.
     */
    public String generateChatCustomerToken(Long sessionId) {
        long nowMs = System.currentTimeMillis();
        return Jwts.builder()
                .subject("chat-customer")
                .issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + CHAT_CUSTOMER_TOKEN_TTL_MS))
                .claim("scope", "chat_customer")
                .claim("sessionId", sessionId)
                .signWith(key(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Valida um token de cliente de chat contra a sessão esperada — nunca lança, só
     * retorna {@code false} pra qualquer problema (assinatura, expiração, scope errado ou
     * sessionId de outra conversa). Nunca aceita o JWT principal de staff nem o de
     * streaming aqui — a claim "scope" distingue os três tipos de token deste serviço.
     */
    public boolean validateChatCustomerToken(String token, Long expectedSessionId) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!"chat_customer".equals(claims.get("scope"))) {
                return false;
            }
            Object sessionIdClaim = claims.get("sessionId");
            long sessionId = sessionIdClaim instanceof Number n ? n.longValue() : Long.parseLong(sessionIdClaim.toString());
            return expectedSessionId != null && sessionId == expectedSessionId;
        } catch (Exception e) {
            return false;
        }
    }
}

