package com.asteriskia.domain.auth.sso;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Proteção de {@code /admin/config} vem só do matcher em {@code SecurityConfig}
 * ({@code hasAnyAuthority("ROLE_ADMIN", "PERM_WRITE_admin.sso")}) — não há
 * {@code @EnableMethodSecurity} configurado no projeto, então qualquer {@code @PreAuthorize}
 * aqui seria código morto (nunca avaliado pelo Spring Security).
 */
@RestController
@RequestMapping("/api/v1/auth/sso")
@RequiredArgsConstructor
public class SsoController {

    private final SsoService ssoService;

    @GetMapping("/config")
    public ResponseEntity<SsoService.SsoPublicConfigDto> getPublicConfig() {
        return ResponseEntity.ok(ssoService.getPublicConfig());
    }

    @GetMapping("/authorize-url")
    public ResponseEntity<String> getAuthorizeUrl(
            @RequestParam(defaultValue = "https://app.voiphash.com.br/login") String redirectUri) {
        return ResponseEntity.ok(ssoService.buildAuthorizeUrl(redirectUri));
    }

    @PostMapping("/callback")
    public ResponseEntity<SsoService.SsoLoginResponseDto> processCallback(
            @RequestBody SsoCallbackRequest request) {
        return ResponseEntity.ok(
                ssoService.processSsoLoginWithCode(request.code(), request.state(), request.redirectUri()));
    }

    @PutMapping("/admin/config")
    public ResponseEntity<SsoConfiguration> updateAdminConfig(
            @RequestBody SsoService.SsoConfigUpdateRequest request) {
        return ResponseEntity.ok(ssoService.updateAdminConfig(request));
    }

    public record SsoCallbackRequest(String code, String state, String redirectUri) {}
}
