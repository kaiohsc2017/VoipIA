package com.asteriskia.config;

import com.asteriskia.domain.user.AppUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * AuthController — Endpoint de autenticação JWT.
 *
 * POST /api/v1/auth/login → busca usuário na tabela app_users (BCrypt),
 * com fallback para as credenciais de ambiente (ADMIN_USERNAME/ADMIN_PASSWORD).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Autenticação JWT")
public class AuthController {

    private final JwtService jwtService;

    /** Repositório de usuários — injetado lazy para evitar circular dependency */
    @Lazy
    @Autowired
    private com.asteriskia.domain.user.AppUserRepository userRepo;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** Fallback admin — configurado via env ADMIN_USERNAME (padrão: admin) */
    @Value("${app.auth.admin-username:admin}")
    private String adminUsername;

    /** Fallback senha admin — configurado via env ADMIN_PASSWORD */
    @Value("${app.auth.admin-password:changeme}")
    private String adminPassword;

    @PostMapping("/login")
    @Operation(summary = "Autenticar usuário e obter token JWT")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {

        // 1. Tenta autenticar via tabela app_users (BCrypt)
        try {
            Optional<AppUser> userOpt = userRepo.findByUsernameAndIsActiveTrue(request.username());
            if (userOpt.isPresent()) {
                AppUser user = userOpt.get();
                if (ENCODER.matches(request.password(), user.getPasswordHash())) {
                    String token = jwtService.generateToken(user.getUsername(), user.getExtension());
                    log.info("Login DB: '{}' → ramal {}", user.getUsername(), user.getExtension());
                    return ResponseEntity.ok(new LoginResponse(
                            token, "Bearer", 8, user.getExtension(), user.getDisplayName()
                    ));
                }
            }
        } catch (Exception e) {
            // Tabela pode não existir ainda — usa fallback abaixo
            log.debug("Fallback para credenciais de ambiente: {}", e.getMessage());
        }

        // 2. Fallback: credenciais de ambiente (compatibilidade retroativa)
        if (adminUsername.equals(request.username()) && adminPassword.equals(request.password())) {
            String token = jwtService.generateToken(request.username(), 9001);
            log.info("Login ENV: '{}' → ramal 9001 (fallback)", request.username());
            return ResponseEntity.ok(new LoginResponse(token, "Bearer", 8, 9001, "Administrador"));
        }

        log.warn("Tentativa de login inválida: '{}'", request.username());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("Credenciais inválidas"));
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

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
