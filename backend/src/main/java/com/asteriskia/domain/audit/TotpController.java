package com.asteriskia.domain.audit;

import com.asteriskia.config.JwtService;
import com.asteriskia.config.RefreshTokenService;
import com.asteriskia.domain.accessgroup.AccessGroupService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * TotpController — Setup e validação de 2FA TOTP para usuários (Fase 13).
 *
 * <p>Fluxo de configuração: 1. POST /api/v1/auth/totp/setup → gera segredo + URL de QR Code 2. POST
 * /api/v1/auth/totp/enable → usuário escaneia QR e confirma com primeiro código 3. (opcional) POST
 * /totp/disable → desativa 2FA
 *
 * <p>Fluxo de login com 2FA ativo: 1. POST /api/v1/auth/login → retorna { requiresTotp: true,
 * tempToken: "..." } 2. POST /api/v1/auth/totp/verify → valida código TOTP e retorna JWT final
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/totp")
@RequiredArgsConstructor
public class TotpController {

    private final TotpService totpService;
    private final AuditService auditService;
    private final AppUserRepository userRepo;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AccessGroupService accessGroupService;

    // ── Setup: gera segredo + QR Code (usuário autenticado) ──────────────────

    @PostMapping("/setup")
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
        return ResponseEntity.ok(
                Map.of(
                        "secret", secret,
                        "qrCodeUrl", qrCodeUrl,
                        "otpAuthUrl", otpAuthUrl,
                        "message",
                                "Escaneie o QR Code com seu app autenticador (Google Authenticator, Authy) "
                                        + "e confirme com o código gerado."));
    }

    // ── Enable: confirma o setup com o primeiro código ────────────────────────

    @PostMapping("/enable")
    public ResponseEntity<?> enable(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        if (user.getTotpSecret() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Execute /setup antes de ativar o 2FA."));
        }

        String code = body.getOrDefault("code", "");
        if (!totpService.verify(user.getTotpSecret(), code)) {
            auditService.logAs(
                    request, username, "TOTP_VERIFY_FAILED", "Falha ao ativar 2FA", false);
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "Código inválido. Verifique o horário do dispositivo e tente novamente."));
        }

        user.setTotpEnabled(true);
        user.setFirstLoginCompleted(true);
        userRepo.save(user);

        auditService.logAs(request, username, "TOTP_ENABLED", "2FA ativado com sucesso", true);
        log.info("2FA TOTP ativado para usuário '{}'", username);
        return ResponseEntity.ok(
                Map.of(
                        "message",
                        "2FA ativado com sucesso! Seu próximo login exigirá o código do autenticador."));
    }

    // ── Disable: desativa o 2FA ───────────────────────────────────────────────

    @PostMapping("/disable")
    public ResponseEntity<?> disable(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getTotpEnabled())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "2FA não está ativo para este usuário."));
        }

        String code = body.getOrDefault("code", "");
        if (!totpService.verify(user.getTotpSecret(), code)) {
            auditService.logAs(
                    request, username, "TOTP_VERIFY_FAILED", "Falha ao desativar 2FA", false);
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
    public ResponseEntity<?> verify(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        String tempToken = body.getOrDefault("tempToken", "");
        String code = body.getOrDefault("code", "");

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
            auditService.logAs(
                    request,
                    username,
                    "TOTP_VERIFY_FAILED",
                    "Código TOTP inválido no login",
                    false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Código de verificação inválido."));
        }

        // Código válido → emite JWT final (com a role real do usuário — antes
        // sempre virava "USER" independente do cargo, trancando admins com 2FA)
        var perms = accessGroupService.permissionsFor(user.getAccessGroup());
        String jwt =
                jwtService.generateToken(
                        user.getUsername(),
                        user.getExtension(),
                        user.getRole(),
                        perms,
                        user.businessUnitIds());
        String newRefreshToken = refreshTokenService.generateRefreshToken(user.getUsername());

        auditService.logAs(request, username, "LOGIN", "Login concluído com 2FA", true);
        log.info("Login 2FA concluído para '{}'", username);

        // Refresh token via cookie httpOnly — nunca no corpo JSON (mesmo padrão do AuthController).
        ResponseCookie cookie =
                ResponseCookie.from("voipia_refresh_token", newRefreshToken)
                        .httpOnly(true)
                        .secure(true)
                        .sameSite("Strict")
                        .path("/api/v1/auth")
                        .maxAge(30L * 24 * 3600)
                        .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(
                        Map.of(
                                "token",
                                jwt,
                                "type",
                                "Bearer",
                                "expiresInHours",
                                8,
                                "extension",
                                user.getExtension(),
                                "displayName",
                                user.getDisplayName(),
                                "firstLoginCompleted",
                                Boolean.TRUE.equals(user.getFirstLoginCompleted())));
    }

    // ── Status do 2FA do usuário logado ──────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(
                Map.of(
                        "totpEnabled", Boolean.TRUE.equals(user.getTotpEnabled()),
                        "firstLoginCompleted", Boolean.TRUE.equals(user.getFirstLoginCompleted()),
                        "username", username));
    }

    /**
     * Marca que o usuário já passou pela oferta de MFA do primeiro login — chamado tanto ao pular a
     * oferta quanto (redundante, sem problema) ao ativar o 2FA pelo fluxo normal de /enable.
     */
    @PostMapping("/first-login-complete")
    public ResponseEntity<?> firstLoginComplete() {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        user.setFirstLoginCompleted(true);
        userRepo.save(user);
        return ResponseEntity.ok(Map.of("firstLoginCompleted", true));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String currentUsername() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null
                    && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception ex) {
            log.debug("Não foi possível resolver o usuário autenticado atual", ex);
        }
        return null;
    }
}
