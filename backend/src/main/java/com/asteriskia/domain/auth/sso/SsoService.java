package com.asteriskia.domain.auth.sso;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SsoService {

    private final SsoConfigurationRepository ssoConfigRepository;
    private final AppUserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public SsoPublicConfigDto getPublicConfig() {
        Optional<SsoConfiguration> opt = ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA");
        if (opt.isPresent() && Boolean.TRUE.equals(opt.get().getIsActive())) {
            SsoConfiguration cfg = opt.get();
            String tenant = (cfg.getTenantId() != null && !cfg.getTenantId().isBlank()) ? cfg.getTenantId() : "common";
            String authUrl = String.format(
                    "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize?client_id=%s&response_type=code&response_mode=query&scope=openid%%20profile%%20email",
                    tenant, cfg.getClientId() != null ? cfg.getClientId() : ""
            );
            return new SsoPublicConfigDto(true, cfg.getDisplayName(), authUrl, cfg.getProviderName());
        }
        return new SsoPublicConfigDto(false, "Microsoft 365 / Entra ID", "", "MICROSOFT_ENTRA");
    }

    public String buildAuthorizeUrl(String redirectUri, String state) {
        SsoConfiguration cfg = ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA")
                .orElseThrow(() -> new IllegalStateException("Configuração SSO Microsoft Entra não encontrada"));

        String tenant = (cfg.getTenantId() != null && !cfg.getTenantId().isBlank()) ? cfg.getTenantId() : "common";
        String encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        String finalState = (state != null && !state.isBlank()) ? state : UUID.randomUUID().toString();

        return String.format(
                "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize?client_id=%s&response_type=code&redirect_uri=%s&response_mode=query&scope=openid%%20profile%%20email&state=%s",
                tenant, cfg.getClientId() != null ? cfg.getClientId() : "", encodedRedirect, finalState
        );
    }

    @Transactional
    public SsoLoginResponseDto processSsoLogin(String email, String displayName) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("E-mail do usuário SSO não fornecido");
        }

        String username = email.trim().toLowerCase();
        Optional<AppUser> userOpt = userRepository.findByUsernameIgnoreCase(username);

        AppUser user;
        if (userOpt.isPresent()) {
            user = userOpt.get();
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                throw new IllegalStateException("Conta de usuário desativada");
            }
        } else {
            // Provisionamento automático de usuário via SSO
            int nextExt = userRepository.findNextExtension(9010);
            user = AppUser.builder()
                    .username(username)
                    .displayName((displayName != null && !displayName.isBlank()) ? displayName : username)
                    .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .extension(nextExt)
                    .isActive(true)
                    .build();
            user = userRepository.save(user);
            log.info("Novo usuário provisionado automaticamente via SSO Microsoft Entra: {} (Ramal {})", username, nextExt);
        }

        String token = jwtService.generateToken(user.getUsername());
        return new SsoLoginResponseDto(
                token,
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getExtension()
        );
    }

    @Transactional
    public SsoConfiguration updateAdminConfig(SsoConfigUpdateRequest req) {
        SsoConfiguration cfg = ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA")
                .orElseGet(() -> SsoConfiguration.builder().providerName("MICROSOFT_ENTRA").build());

        if (req.displayName() != null) cfg.setDisplayName(req.displayName());
        if (req.clientId() != null) cfg.setClientId(req.clientId());
        if (req.clientSecret() != null && !req.clientSecret().isBlank()) cfg.setClientSecret(req.clientSecret());
        if (req.tenantId() != null) cfg.setTenantId(req.tenantId());
        if (req.redirectUri() != null) cfg.setRedirectUri(req.redirectUri());
        if (req.autoProvisionUsers() != null) cfg.setAutoProvisionUsers(req.autoProvisionUsers());
        if (req.isActive() != null) cfg.setIsActive(req.isActive());
        cfg.setUpdatedAt(Instant.now());

        return ssoConfigRepository.save(cfg);
    }

    public record SsoPublicConfigDto(boolean enabled, String displayName, String authorizationUrl, String provider) {}

    public record SsoLoginResponseDto(String token, Integer id, String username, String displayName, Integer extension) {}

    public record SsoConfigUpdateRequest(
            String displayName,
            String clientId,
            String clientSecret,
            String tenantId,
            String redirectUri,
            Boolean autoProvisionUsers,
            Boolean isActive
    ) {}
}
