package com.asteriskia.domain.auth.sso;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(defaultValue = "https://app.voiphash.com.br/login") String redirectUri,
            @RequestParam(required = false) String state) {
        return ResponseEntity.ok(ssoService.buildAuthorizeUrl(redirectUri, state));
    }

    @PostMapping("/callback")
    public ResponseEntity<SsoService.SsoLoginResponseDto> processCallback(
            @RequestBody SsoCallbackRequest request) {
        return ResponseEntity.ok(ssoService.processSsoLogin(request.email(), request.name()));
    }

    @PutMapping("/admin/config")
    @PreAuthorize("hasAuthority('admin.sso:write') or hasRole('ADMIN')")
    public ResponseEntity<SsoConfiguration> updateAdminConfig(
            @RequestBody SsoService.SsoConfigUpdateRequest request) {
        return ResponseEntity.ok(ssoService.updateAdminConfig(request));
    }

    public record SsoCallbackRequest(String email, String name, String code, String state) {}
}
