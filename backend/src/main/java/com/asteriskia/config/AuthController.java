package com.asteriskia.config;

import com.asteriskia.domain.accessgroup.AccessGroupService;
import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * AuthController — Endpoint de autenticação JWT com suporte a 2FA TOTP (Fase 13).
 *
 * POST /api/v1/auth/login
 *   - Se 2FA inativo  → retorna JWT normal.
 *   - Se 2FA ativo    → retorna { requiresTotp: true, tempToken: "..." }
 *                        (usuário deve chamar POST /api/v1/auth/totp/verify)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService          jwtService;
    private final AppUserRepository   userRepo;
    private final AuditService        auditService;
    private final RefreshTokenService refreshTokenService;
    private final AccessGroupService  accessGroupService;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Value("${app.auth.admin-username:admin}")
    private String adminUsername;

    @Value("${app.auth.admin-password:changeme}")
    private String adminPassword;

    private static final String REFRESH_COOKIE = "asteriskia_refresh_token";

    /**
     * Cookie httpOnly com o refresh token — nunca acessível via JavaScript
     * (mitiga exfiltração via XSS pontual, diferente do access token de vida
     * curta). Escopado a /api/v1/auth para não ser enviado em toda requisição.
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
        public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest) {

        // 1. Tenta autenticar via tabela app_users (BCrypt)
        try {
            Optional<AppUser> userOpt = userRepo.findByUsernameAndIsActiveTrue(request.username());
            if (userOpt.isPresent()) {
                AppUser user = userOpt.get();
                if (ENCODER.matches(request.password(), user.getPasswordHash())) {
                    return handleSuccessfulLogin(user, httpRequest);
                }
            }
        } catch (Exception e) {
            // WARN (não DEBUG): em produção o nível padrão é INFO — sem isso, uma
            // falha no AppUserRepository (ex: banco fora do ar) cairia no fallback
            // silenciosamente, sem nenhum rastro visível no log.
            log.warn("Fallback para credenciais de ambiente: {}", e.getMessage());
        }

        // 2. Fallback: credenciais de ambiente (compatibilidade retroativa)
        if (adminUsername.equals(request.username()) && adminPassword.equals(request.password())) {
            // Fallback via env é sempre a conta mestre — tratado como ADMIN.
            var envPerms = accessGroupService.permissionsFor(accessGroupService.administradores());
            String token = jwtService.generateToken(request.username(), 9001, "ADMIN", envPerms);
            String refreshToken = refreshTokenService.generateRefreshToken(request.username());
            auditService.logAs(httpRequest, request.username(), "LOGIN",
                    "Login via variáveis de ambiente (fallback)", true);
            log.info("Login ENV: '{}' → ramal 9001 (fallback)", request.username());
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie(refreshToken, 30L * 24 * 3600).toString())
                    .body(new LoginResponse(token, "Bearer", 8, 9001, "Administrador"));
        }

        // 3. Credenciais inválidas
        auditService.logAs(httpRequest, request.username(), "LOGIN_FAILED",
                "Tentativa com credenciais inválidas", false);
        log.warn("Tentativa de login inválida: '{}'", request.username());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("Credenciais inválidas"));
    }

    private ResponseEntity<?> handleSuccessfulLogin(AppUser user, HttpServletRequest request) {
        // Se 2FA está ativo, emite token temporário (5 min) e retorna requiresTotp=true
        if (Boolean.TRUE.equals(user.getTotpEnabled())) {
            String tempToken = jwtService.generateTempToken(user.getUsername());
            auditService.logAs(request, user.getUsername(), "LOGIN",
                    "Primeira etapa do login concluída — aguardando TOTP", true);
            log.info("Login DB: '{}' — 2FA ativo, aguardando TOTP", user.getUsername());
            return ResponseEntity.ok(Map.of(
                    "requiresTotp", true,
                    "tempToken",    tempToken,
                    "displayName",  user.getDisplayName()
            ));
        }

        // Login normal (sem 2FA)
        var perms = accessGroupService.permissionsFor(user.getAccessGroup());
        String token = jwtService.generateToken(user.getUsername(), user.getExtension(), user.getRole(), perms);
        String refreshToken = refreshTokenService.generateRefreshToken(user.getUsername());
        auditService.logAs(request, user.getUsername(), "LOGIN",
                "Login bem-sucedido (ramal " + user.getExtension() + ")", true);
        log.info("Login DB: '{}' → ramal {}", user.getUsername(), user.getExtension());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(refreshToken, 30L * 24 * 3600).toString())
                .body(new LoginResponse(token, "Bearer", 8, user.getExtension(), user.getDisplayName()));
    }

    @PostMapping("/refresh")
        public ResponseEntity<?> refresh(
                @CookieValue(name = REFRESH_COOKIE, required = false) String reqRefreshToken) {
        if (reqRefreshToken == null || reqRefreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Refresh token não fornecido"));
        }

        Optional<RefreshToken> optToken = refreshTokenService.validateRefreshToken(reqRefreshToken);
        if (optToken.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Refresh token expirado ou inválido"));
        }

        RefreshToken refreshToken = optToken.get();
        String username = refreshToken.getUsername();

        Integer extension = 9001;
        String displayName = "Administrador";
        String role = adminUsername.equals(username) ? "ADMIN" : "USER";
        var perms = accessGroupService.permissionsFor(accessGroupService.administradores());

        Optional<AppUser> userOpt = userRepo.findByUsernameAndIsActiveTrue(username);
        if (userOpt.isPresent()) {
            extension = userOpt.get().getExtension();
            displayName = userOpt.get().getDisplayName();
            role = userOpt.get().getRole();
            perms = accessGroupService.permissionsFor(userOpt.get().getAccessGroup());
        }

        // Rotação: revoga o antigo e gera um novo
        refreshTokenService.revokeRefreshToken(reqRefreshToken);
        String newJwt = jwtService.generateToken(username, extension, role, perms);
        String newRefreshToken = refreshTokenService.generateRefreshToken(username);

        log.info("Token renovado via refresh para '{}'", username);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(newRefreshToken, 30L * 24 * 3600).toString())
                .body(new LoginResponse(newJwt, "Bearer", 8, extension, displayName));
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

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}

    public record LoginResponse(
            String token,
            String type,
            int expiresInHours,
            Integer extension,
            String displayName
    ) {}

    public record ErrorResponse(String message) {}
}
