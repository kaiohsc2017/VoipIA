package com.asteriskia.config;

import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
@Tag(name = "Auth", description = "Autenticação JWT")
public class AuthController {

    private final JwtService          jwtService;
    private final AppUserRepository   userRepo;
    private final AuditService        auditService;
    private final RefreshTokenService refreshTokenService;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Value("${app.auth.admin-username:admin}")
    private String adminUsername;

    @Value("${app.auth.admin-password:changeme}")
    private String adminPassword;

    @PostMapping("/login")
    @Operation(summary = "Autenticar usuário e obter token JWT")
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
            log.debug("Fallback para credenciais de ambiente: {}", e.getMessage());
        }

        // 2. Fallback: credenciais de ambiente (compatibilidade retroativa)
        if (adminUsername.equals(request.username()) && adminPassword.equals(request.password())) {
            String token = jwtService.generateToken(request.username(), 9001);
            String refreshToken = refreshTokenService.generateRefreshToken(request.username());
            auditService.logAs(httpRequest, request.username(), "LOGIN",
                    "Login via variáveis de ambiente (fallback)", true);
            log.info("Login ENV: '{}' → ramal 9001 (fallback)", request.username());
            return ResponseEntity.ok(new LoginResponse(token, refreshToken, "Bearer", 8, 9001, "Administrador"));
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
        String token = jwtService.generateToken(user.getUsername(), user.getExtension());
        String refreshToken = refreshTokenService.generateRefreshToken(user.getUsername());
        auditService.logAs(request, user.getUsername(), "LOGIN",
                "Login bem-sucedido (ramal " + user.getExtension() + ")", true);
        log.info("Login DB: '{}' → ramal {}", user.getUsername(), user.getExtension());
        return ResponseEntity.ok(new LoginResponse(
                token, refreshToken, "Bearer", 8, user.getExtension(), user.getDisplayName()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renovar token JWT usando o Refresh Token")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String reqRefreshToken = body.get("refreshToken");
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
        
        Optional<AppUser> userOpt = userRepo.findByUsernameAndIsActiveTrue(username);
        if (userOpt.isPresent()) {
            extension = userOpt.get().getExtension();
            displayName = userOpt.get().getDisplayName();
        }

        // Rotação: revoga o antigo e gera um novo
        refreshTokenService.revokeRefreshToken(reqRefreshToken);
        String newJwt = jwtService.generateToken(username, extension);
        String newRefreshToken = refreshTokenService.generateRefreshToken(username);

        log.info("Token renovado via refresh para '{}'", username);
        return ResponseEntity.ok(new LoginResponse(
                newJwt, newRefreshToken, "Bearer", 8, extension, displayName));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout (revoga refresh token)")
    public ResponseEntity<?> logout(@RequestBody Map<String, String> body) {
        String reqRefreshToken = body.get("refreshToken");
        if (reqRefreshToken != null && !reqRefreshToken.isBlank()) {
            refreshTokenService.revokeRefreshToken(reqRefreshToken);
            log.info("Logout: refresh token revogado");
        }
        return ResponseEntity.ok().build();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}

    public record LoginResponse(
            String token,
            String refreshToken,
            String type,
            int expiresInHours,
            Integer extension,
            String displayName
    ) {}

    public record ErrorResponse(String message) {}
}
