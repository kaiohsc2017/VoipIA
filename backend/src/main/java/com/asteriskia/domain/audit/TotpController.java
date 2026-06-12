package com.asteriskia.domain.audit;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * TotpController — Setup e validação de 2FA TOTP para usuários (Fase 13).
 *
 * Fluxo de configuração:
 *   1. POST /api/v1/auth/totp/setup     → gera segredo + URL de QR Code
 *   2. POST /api/v1/auth/totp/enable    → usuário escaneia QR e confirma com primeiro código
 *   3. (opcional) POST /totp/disable    → desativa 2FA
 *
 * Fluxo de login com 2FA ativo:
 *   1. POST /api/v1/auth/login          → retorna { requiresTotp: true, tempToken: "..." }
 *   2. POST /api/v1/auth/totp/verify    → valida código TOTP e retorna JWT final
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/totp")
@RequiredArgsConstructor
@Tag(name = "2FA TOTP", description = "Configuração e validação de dois fatores (Fase 13)")
public class TotpController {

    private final TotpService       totpService;
    private final AuditService      auditService;
    private final AppUserRepository userRepo;
    private final JwtService        jwtService;

    // ── Setup: gera segredo + QR Code (usuário autenticado) ──────────────────

    @PostMapping("/setup")
    @Operation(summary = "Gera segredo TOTP e URL de QR Code para configuração")
    public ResponseEntity<?> setup(HttpServletRequest request) {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        // Gera novo segredo (não ativa ainda)
        String secret = totpService.generateSecret();
        user.setTotpSecret(secret);
        user.setTotpEnabled(false);
        userRepo.save(user);

        String qrCodeUrl = totpService.buildQrCodeUrl(username, secret);
        String otpAuthUrl = totpService.buildOtpAuthUrl(username, secret);

        log.info("Setup TOTP iniciado para usuário '{}'", username);
        return ResponseEntity.ok(Map.of(
                "secret",     secret,
                "qrCodeUrl",  qrCodeUrl,
                "otpAuthUrl", otpAuthUrl,
                "message",    "Escaneie o QR Code com seu app autenticador (Google Authenticator, Authy) e confirme com o código gerado."
        ));
    }

    // ── Enable: confirma o setup com o primeiro código ────────────────────────

    @PostMapping("/enable")
    @Operation(summary = "Ativa o 2FA após confirmação com o primeiro código TOTP")
    public ResponseEntity<?> enable(@RequestBody Map<String, String> body,
                                    HttpServletRequest request) {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        if (user.getTotpSecret() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Execute /setup antes de ativar o 2FA."));
        }

        String code = body.getOrDefault("code", "");
        if (!totpService.verify(user.getTotpSecret(), code)) {
            auditService.logAs(request, username, "TOTP_VERIFY_FAILED", "Falha ao ativar 2FA", false);
            return ResponseEntity.badRequest().body(Map.of("error", "Código inválido. Verifique o horário do dispositivo e tente novamente."));
        }

        user.setTotpEnabled(true);
        userRepo.save(user);

        auditService.logAs(request, username, "TOTP_ENABLED", "2FA ativado com sucesso", true);
        log.info("2FA TOTP ativado para usuário '{}'", username);
        return ResponseEntity.ok(Map.of("message", "2FA ativado com sucesso! Seu próximo login exigirá o código do autenticador."));
    }

    // ── Disable: desativa o 2FA ───────────────────────────────────────────────

    @PostMapping("/disable")
    @Operation(summary = "Desativa o 2FA (exige confirmação com código TOTP atual)")
    public ResponseEntity<?> disable(@RequestBody Map<String, String> body,
                                     HttpServletRequest request) {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getTotpEnabled())) {
            return ResponseEntity.badRequest().body(Map.of("error", "2FA não está ativo para este usuário."));
        }

        String code = body.getOrDefault("code", "");
        if (!totpService.verify(user.getTotpSecret(), code)) {
            auditService.logAs(request, username, "TOTP_VERIFY_FAILED", "Falha ao desativar 2FA", false);
            return ResponseEntity.badRequest().body(Map.of("error", "Código inválido."));
        }

        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        userRepo.save(user);

        auditService.logAs(request, username, "TOTP_DISABLED", "2FA desativado", true);
        log.info("2FA TOTP desativado para usuário '{}'", username);
        return ResponseEntity.ok(Map.of("message", "2FA desativado."));
    }

    // ── Verify: segunda etapa do login (valida código + retorna JWT final) ────

    @PostMapping("/verify")
    @Operation(summary = "Segunda etapa do login: valida código TOTP e retorna JWT")
    public ResponseEntity<?> verify(@RequestBody Map<String, String> body,
                                    HttpServletRequest request) {
        String tempToken = body.getOrDefault("tempToken", "");
        String code      = body.getOrDefault("code", "");

        // Valida o temp token (JWT com claim "totp_pending=true")
        String username;
        try {
            username = jwtService.extractUsername(tempToken);
            if (!jwtService.isTotpPending(tempToken)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Token inválido."));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Token expirado ou inválido."));
        }

        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getTotpEnabled())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!totpService.verify(user.getTotpSecret(), code)) {
            auditService.logAs(request, username, "TOTP_VERIFY_FAILED",
                    "Código TOTP inválido no login", false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Código de verificação inválido."));
        }

        // Código válido → emite JWT final
        String jwt = jwtService.generateToken(user.getUsername(), user.getExtension());
        auditService.logAs(request, username, "LOGIN",
                "Login concluído com 2FA", true);
        log.info("Login 2FA concluído para '{}'", username);

        return ResponseEntity.ok(Map.of(
                "token",        jwt,
                "type",         "Bearer",
                "expiresInHours", 8,
                "extension",    user.getExtension(),
                "displayName",  user.getDisplayName()
        ));
    }

    // ── Status do 2FA do usuário logado ──────────────────────────────────────

    @GetMapping("/status")
    @Operation(summary = "Retorna se o 2FA está ativo para o usuário autenticado")
    public ResponseEntity<?> status() {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "totpEnabled", Boolean.TRUE.equals(user.getTotpEnabled()),
                "username",    username
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String currentUsername() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
