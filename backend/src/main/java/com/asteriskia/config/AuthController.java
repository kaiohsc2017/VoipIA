package com.asteriskia.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AuthController — Endpoint de autenticação JWT.
 *
 * POST /api/v1/auth/login → valida usuário/senha e retorna token JWT
 *
 * Credenciais configuradas via application.properties (variáveis de ambiente).
 * Para ambientes produtivos, substituir por tabela de usuários no banco.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Autenticação JWT")
public class AuthController {

    private final JwtService jwtService;

    /** Usuário admin — configurado via env ADMIN_USERNAME (padrão: admin) */
    @Value("${app.auth.admin-username:admin}")
    private String adminUsername;

    /** Senha admin — configurada via env ADMIN_PASSWORD */
    @Value("${app.auth.admin-password:changeme}")
    private String adminPassword;

    /**
     * Realiza login e retorna um token JWT válido.
     *
     * @param request Credenciais (username + password)
     * @return Token JWT + tipo + expiração em horas
     */
    @PostMapping("/login")
    @Operation(summary = "Autenticar usuário e obter token JWT")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        if (adminUsername.equals(request.username()) && adminPassword.equals(request.password())) {
            String token = jwtService.generateToken(request.username());
            log.info("Login bem-sucedido para '{}'", request.username());
            return ResponseEntity.ok(new LoginResponse(token, "Bearer", 8));
        }
        log.warn("Tentativa de login inválida para '{}'", request.username());
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
            int expiresInHours
    ) {}

    public record ErrorResponse(String message) {}
}
