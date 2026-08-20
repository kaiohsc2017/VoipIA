package com.asteriskia.config;

import com.asteriskia.domain.accessgroup.AccessGroupService;
import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import com.asteriskia.integration.ad.AdUserService;
import com.asteriskia.integration.ad.LdapClient;
import com.asteriskia.integration.ad.LdapUserAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.text.Normalizer;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * AuthController — Endpoint de autenticação JWT com suporte a 2FA TOTP (Fase 13).
 *
 * <p>POST /api/v1/auth/login - Se 2FA inativo → retorna JWT normal. - Se 2FA ativo → retorna {
 * requiresTotp: true, tempToken: "..." } (usuário deve chamar POST /api/v1/auth/totp/verify)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;
    private final AppUserRepository userRepo;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final AccessGroupService accessGroupService;
    private final LdapClient ldapClient;
    private final AdUserService adUserService;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final int EXTENSION_START = 9001;

    @Value("${app.auth.admin-username:admin}")
    private String adminUsername;

    @Value("${app.auth.admin-password:changeme}")
    private String adminPassword;

    @Value("${app.jwt.refresh-expiration-days:7}")
    private int refreshExpirationDays;

    private static final String REFRESH_COOKIE = "voipia_refresh_token";

    /**
     * Cookie httpOnly com o refresh token — nunca acessível via JavaScript (mitiga exfiltração via
     * XSS pontual, diferente do access token de vida curta). Escopado a /api/v1/auth para não ser
     * enviado em toda requisição.
     */
    private ResponseCookie refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAgeSeconds)
                .build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

        final String rawUsername = request.username() != null ? request.username() : "";
        final String username = Normalizer.normalize(rawUsername, Normalizer.Form.NFKC).trim();
        final String rawPassword = request.password() != null ? request.password() : "";
        final String normalizedPassword = Normalizer.normalize(rawPassword, Normalizer.Form.NFKC)
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .replace("\r", "")
                .replace("\n", "");
        final String trimmedPassword = normalizedPassword.trim();

        // 1. Tenta autenticar via tabela app_users (BCrypt)
        try {
            Optional<AppUser> userOpt = userRepo.findByUsernameIgnoreCaseAndIsActiveTrue(username);
            if (userOpt.isPresent()) {
                AppUser user = userOpt.get();
                boolean matches = ENCODER.matches(normalizedPassword, user.getPasswordHash())
                        || ENCODER.matches(trimmedPassword, user.getPasswordHash())
                        || ENCODER.matches(rawPassword, user.getPasswordHash());

                log.info("Auth attempt: user='{}', db_user='{}', matches={}",
                        username, user.getUsername(), matches);

                if (matches) {
                    if (user.hasExpiredAccess()) {
                        auditService.logAs(
                                httpRequest,
                                user.getUsername(),
                                "LOGIN_FAILED",
                                "Acesso expirado em " + user.getAccessExpiresAt(),
                                false);
                        log.warn(
                                "Login bloqueado: acesso de '{}' expirado em {}",
                                user.getUsername(),
                                user.getAccessExpiresAt());
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(
                                        new ErrorResponse(
                                                "Acesso expirado. Contate o administrador."));
                    }
                    return handleSuccessfulLogin(user, httpRequest);
                }
            }
        } catch (Exception e) {
            // WARN (não DEBUG): em produção o nível padrão é INFO — sem isso, uma
            // falha no AppUserRepository (ex: banco fora do ar) cairia no fallback
            // silenciosamente, sem nenhum rastro visível no log.
            log.warn("Fallback para credenciais de ambiente: {}", e.getMessage());
        }

        // 1bis. Fallback AD/LDAP (módulo Call Center, Fase 1) — só entra em jogo se a etapa 1
        // não encontrou/autenticou o usuário localmente. Nunca sobrescreve um usuário local
        // desativado com o mesmo username (checagem por findByUsername, não só *AndIsActiveTrue).
        // CRÍTICO (achado de segurança): uma conta local pré-existente só pode ser autenticada
        // pelo bind AD se já foi provisionada via AD (adLinked=true) — senão, qualquer pessoa que
        // soubesse a senha AD daquele username sequestraria a conta local sem nunca validar a
        // senha local (BCrypt). Conta local "nativa" (criada pela tela Usuários) nunca aceita AD.
        if (ldapClient.currentConfig().enabled()) {
            var adAttrsOpt = ldapClient.authenticate(username, normalizedPassword);
            if (adAttrsOpt.isEmpty() && !normalizedPassword.equals(rawPassword)) {
                adAttrsOpt = ldapClient.authenticate(username, rawPassword);
            }
            if (adAttrsOpt.isPresent()) {
                LdapUserAttributes attrs = adAttrsOpt.get();
                adUserService.upsertMirror(attrs);
                Optional<AppUser> existing = userRepo.findByUsernameIgnoreCase(username);
                if (existing.isPresent() && !Boolean.TRUE.equals(existing.get().getIsActive())) {
                    // Conta local desativada — não deixa o bind AD contornar a desativação.
                    auditService.logAs(
                            httpRequest,
                            username,
                            "LOGIN_FAILED",
                            "Bind AD ok, mas conta local está desativada",
                            false);
                } else if (existing.isPresent() && !Boolean.TRUE.equals(existing.get().getAdLinked())) {
                    // Conta local nativa com o mesmo username de uma conta AD — bind AD ok não
                    // autentica esta conta; segue para os demais fallbacks (nunca handleSuccessfulLogin).
                    auditService.logAs(
                            httpRequest,
                            username,
                            "LOGIN_FAILED",
                            "Bind AD ok, mas conta local não está vinculada ao AD (adLinked=false)",
                            false);
                } else {
                    AppUser adUser = existing.orElseGet(() -> provisionAdUser(attrs));
                    if (adUser.hasExpiredAccess()) {
                        auditService.logAs(
                                httpRequest,
                                adUser.getUsername(),
                                "LOGIN_FAILED",
                                "Acesso expirado em " + adUser.getAccessExpiresAt(),
                                false);
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(new ErrorResponse("Acesso expirado. Contate o administrador."));
                    }
                    return handleSuccessfulLogin(adUser, httpRequest);
                }
            }
        }

        // 2. Fallback: credenciais de ambiente (compatibilidade retroativa)
        if (adminUsername.equalsIgnoreCase(username)
                && (adminPassword.equals(normalizedPassword)
                        || adminPassword.equals(trimmedPassword)
                        || adminPassword.equals(rawPassword))) {
            // Fallback via env é sempre a conta mestre — tratado como ADMIN.
            var envPerms = accessGroupService.permissionsFor(accessGroupService.administradores());
            String token = jwtService.generateToken(username, 9001, "ADMIN", envPerms);
            String refreshToken = refreshTokenService.generateRefreshToken(username);
            auditService.logAs(
                    httpRequest,
                    username,
                    "LOGIN",
                    "Login via variáveis de ambiente (fallback)",
                    true);
            log.info("Login ENV: '{}' → ramal 9001 (fallback)", username);
            return ResponseEntity.ok()
                    .header(
                            HttpHeaders.SET_COOKIE,
                            refreshCookie(refreshToken, (long) refreshExpirationDays * 24 * 3600).toString())
                    .body(new LoginResponse(token, "Bearer", 8, 9001, "Administrador", true));
        }

        // 3. Credenciais inválidas
        auditService.logAs(
                httpRequest,
                username,
                "LOGIN_FAILED",
                "Tentativa com credenciais inválidas",
                false);
        log.warn("Tentativa de login inválida: '{}'", username);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("Credenciais inválidas"));
    }

    private AppUser provisionAdUser(LdapUserAttributes attrs) {
        var group =
                adUserService.resolveAccessGroup(
                        attrs.memberOf(), ldapClient.currentConfig().defaultAccessGroupId());
        int extension = userRepo.findNextExtension(EXTENSION_START);
        AppUser user =
                AppUser.builder()
                        .username(attrs.samAccountName())
                        .displayName(
                                attrs.displayName() != null
                                        ? attrs.displayName()
                                        : attrs.samAccountName())
                        .passwordHash(ENCODER.encode(java.util.UUID.randomUUID().toString()))
                        .extension(extension)
                        .role("USER")
                        .accessGroup(group)
                        .isActive(true)
                        .accessIndeterminate(true)
                        .firstLoginCompleted(false)
                        .adLinked(true)
                        .build();
        return userRepo.save(user);
    }

    private ResponseEntity<?> handleSuccessfulLogin(
            AppUser user, HttpServletRequest request) {
        // Se 2FA está ativo
        if (Boolean.TRUE.equals(user.getTotpEnabled())) {
            String tempToken = jwtService.generateTempToken(user.getUsername());
            auditService.logAs(
                    request,
                    user.getUsername(),
                    "LOGIN",
                    "Primeira etapa do login concluída — aguardando TOTP",
                    true);
            return ResponseEntity.ok(
                    Map.of(
                            "requiresTotp",
                            true,
                            "tempToken",
                            tempToken,
                            "displayName",
                            user.getDisplayName()));
        }

        String token =
                jwtService.generateToken(
                        user.getUsername(),
                        user.getExtension(),
                        user.getRole(),
                        accessGroupService.permissionsFor(user.getAccessGroup()),
                        user.businessUnitIds());
        String refreshToken = refreshTokenService.generateRefreshToken(user.getUsername());
        auditService.logAs(
                request,
                user.getUsername(),
                "LOGIN",
                "Login bem-sucedido (ramal " + user.getExtension() + ")",
                true);
        log.info("Login DB: '{}' → ramal {}", user.getUsername(), user.getExtension());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie(refreshToken, (long) refreshExpirationDays * 24 * 3600).toString())
                .body(
                        new LoginResponse(
                                token,
                                "Bearer",
                                8,
                                user.getExtension(),
                                user.getDisplayName(),
                                Boolean.TRUE.equals(user.getFirstLoginCompleted())));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String reqRefreshToken) {
        if (reqRefreshToken == null || reqRefreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Refresh token não fornecido"));
        }

        Optional<RefreshToken> optToken = refreshTokenService.validateRefreshToken(reqRefreshToken);
        if (optToken.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Refresh token expirado ou inválido"));
        }

        RefreshToken refreshToken = optToken.get();
        String username = refreshToken.getUsername();

        boolean isEnvFallbackAdmin = adminUsername.equals(username);
        Optional<AppUser> userOpt = userRepo.findByUsernameAndIsActiveTrue(username);

        // Se o usuário não for o admin de fallback e não existir/estiver inativo no banco:
        if (!isEnvFallbackAdmin && userOpt.isEmpty()) {
            refreshTokenService.revokeRefreshToken(reqRefreshToken);
            log.warn("Refresh bloqueado: usuário '{}' inativo ou inexistente", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Conta inativa ou inexistente."));
        }

        Integer extension = 9001;
        String displayName = "Administrador";
        String role = isEnvFallbackAdmin ? "ADMIN" : "USER";
        var perms =
                isEnvFallbackAdmin
                        ? accessGroupService.permissionsFor(accessGroupService.administradores())
                        : java.util.Map.<String, String>of();
        Set<Integer> businessUnitIds = Set.of();
        boolean firstLoginCompleted = true;

        if (userOpt.isPresent()) {
            AppUser user = userOpt.get();
            if (user.hasExpiredAccess()) {
                refreshTokenService.revokeRefreshToken(reqRefreshToken);
                log.warn(
                        "Refresh bloqueado: acesso de '{}' expirado em {}",
                        username,
                        user.getAccessExpiresAt());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Acesso expirado. Contate o administrador."));
            }
            extension = user.getExtension();
            displayName = user.getDisplayName();
            role = user.getRole();
            perms = accessGroupService.permissionsFor(user.getAccessGroup());
            businessUnitIds = user.businessUnitIds();
            firstLoginCompleted = Boolean.TRUE.equals(user.getFirstLoginCompleted());
        }

        refreshTokenService.revokeRefreshToken(reqRefreshToken);
        String newJwt = jwtService.generateToken(username, extension, role, perms, businessUnitIds);
        String newRefreshToken = refreshTokenService.generateRefreshToken(username);

        log.info("Token renovado via refresh para '{}'", username);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie(newRefreshToken, (long) refreshExpirationDays * 24 * 3600).toString())
                .body(
                        new LoginResponse(
                                newJwt, "Bearer", 8, extension, displayName, firstLoginCompleted));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String reqRefreshToken) {
        if (reqRefreshToken != null && !reqRefreshToken.isBlank()) {
            refreshTokenService.revokeRefreshToken(reqRefreshToken);
            log.info("Logout: refresh token revogado");
        }
        // Expira o cookie imediatamente (maxAge=0) para o navegador descartá-lo.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", 0).toString())
                .build();
    }

    /**
     * Emite um token de streaming de vida curta (60s), usado por EventSource (SSE) e WebSocket —
     * APIs de browser que não permitem header Authorization customizado, então precisam do token na
     * query string. Reflete a mesma role/perm do JWT principal de quem chama (JwtAuthFilter já
     * validou o Bearer token antes de chegar aqui — reextrai as claims do próprio header pra não
     * depender de reconstruir authorities do SecurityContext).
     *
     * <p>Achado da revisão de segurança: exige que o token de ENTRADA seja o principal, nunca outro
     * streaming token — sem essa checagem, um streaming token vazado (o cenário que esta feature
     * existe pra mitigar) poderia ser renovado indefinidamente chamando este endpoint a cada <60s,
     * recuperando validade essencialmente ilimitada a partir de um único vazamento e anulando o
     * propósito do TTL curto.
     */
    @PostMapping("/streaming-token")
    public ResponseEntity<?> streamingToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Não autenticado"));
        }
        String mainToken = auth.substring(7);
        if (!jwtService.isValid(mainToken) || jwtService.isStreamingScope(mainToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Token inválido ou expirado"));
        }
        String username = jwtService.extractUsername(mainToken);
        String role = jwtService.extractRole(mainToken);
        Map<String, String> perms = jwtService.extractPermissions(mainToken);
        var businessUnitIds = jwtService.extractBusinessUnitIds(mainToken);
        String token = jwtService.generateStreamingToken(username, role, perms, businessUnitIds);
        return ResponseEntity.ok(new StreamingTokenResponse(token, 60));
    }
}
