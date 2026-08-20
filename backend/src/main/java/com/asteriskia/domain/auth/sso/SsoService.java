package com.asteriskia.domain.auth.sso;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.accessgroup.AccessGroupService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * SsoService — login corporativo via Microsoft Entra ID (OIDC Authorization Code).
 *
 * <p>O parâmetro {@code state} emitido em {@link #buildAuthorizeUrl} é guardado em memória com
 * TTL curto e validado no callback ({@link #processSsoLoginWithCode}) — proteção contra CSRF de
 * login (um atacante não pode induzir a vítima a completar o callback com um {@code code} que não
 * corresponde à autorização que ela mesma iniciou). Mesmo padrão de cache em memória com limpeza
 * de entradas expiradas já usado em {@code UraRoutingService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoService {

    private static final long STATE_TTL_SECONDS = 600; // 10 min — tempo generoso para o usuário completar o login no IdP

    private final SsoConfigurationRepository ssoConfigRepository;
    private final AppUserRepository userRepository;
    private final com.asteriskia.domain.accessgroup.AccessGroupRepository accessGroupRepository;
    private final AccessGroupService accessGroupService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    private final RestTemplate restTemplate = buildRestTemplate();
    private final Map<String, Instant> pendingStates = new ConcurrentHashMap<>();

    private static RestTemplate buildRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

    public SsoPublicConfigDto getPublicConfig() {
        Optional<SsoConfiguration> opt = ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA");
        if (opt.isPresent() && Boolean.TRUE.equals(opt.get().getIsActive())) {
            SsoConfiguration cfg = opt.get();
            return new SsoPublicConfigDto(true, cfg.getDisplayName(), cfg.getProviderName());
        }
        return new SsoPublicConfigDto(false, "Microsoft 365 / Entra ID", "MICROSOFT_ENTRA");
    }

    public String buildAuthorizeUrl(String redirectUri) {
        SsoConfiguration cfg = ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA")
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new IllegalStateException("Configuração SSO Microsoft Entra não encontrada ou desativada"));

        String tenant = (cfg.getTenantId() != null && !cfg.getTenantId().isBlank()) ? cfg.getTenantId() : "common";
        // Sempre o redirect_uri configurado pelo admin — o valor do chamador nunca é usado aqui,
        // evitando que um cliente escolha, dentre múltiplos redirect_uri registrados no App
        // Registration do Entra (ex: staging vs. produção), qual endpoint recebe o fluxo.
        String targetRedirect = (cfg.getRedirectUri() != null && !cfg.getRedirectUri().isBlank())
                ? cfg.getRedirectUri()
                : "https://app.voiphash.com.br/login";
        String encodedRedirect = URLEncoder.encode(targetRedirect, StandardCharsets.UTF_8);

        String state = UUID.randomUUID().toString();
        cleanupExpiredStates();
        pendingStates.put(state, Instant.now());

        return String.format(
                "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize?client_id=%s&response_type=code&redirect_uri=%s&response_mode=query&scope=openid%%20profile%%20email&state=%s",
                tenant, cfg.getClientId() != null ? cfg.getClientId() : "", encodedRedirect, state
        );
    }

    public SsoLoginResponseDto processSsoLoginWithCode(String code, String state, String redirectUri) {
        if (code == null || code.isBlank()) {
            throw new SecurityException("Código de autorização OAuth2/OIDC ausente.");
        }
        if (!consumeValidState(state)) {
            throw new SecurityException("Parâmetro state ausente, inválido ou expirado — reinicie o login.");
        }

        SsoConfiguration cfg = ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA")
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new SecurityException("Provedor SSO Microsoft Entra desativado ou não configurado."));

        String tenant = (cfg.getTenantId() != null && !cfg.getTenantId().isBlank()) ? cfg.getTenantId() : "common";
        String tokenEndpoint = String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/token", tenant);

        // Mesma decisão de buildAuthorizeUrl: sempre o redirect_uri configurado pelo admin.
        String effectiveRedirect = (cfg.getRedirectUri() != null && !cfg.getRedirectUri().isBlank())
                ? cfg.getRedirectUri()
                : "https://app.voiphash.com.br/login";

        String[] identity = exchangeCodeForIdentity(cfg, tokenEndpoint, code, effectiveRedirect);
        String email = identity[0];
        String displayName = identity[1];

        if (email == null || email.isBlank()) {
            throw new SecurityException("Não foi possível obter o e-mail autenticado do usuário via Microsoft Entra.");
        }

        String username = email.trim().toLowerCase();
        AppUser user = resolveOrProvisionUser(cfg, username, displayName);

        String token = jwtService.generateToken(
                user.getUsername(),
                user.getExtension(),
                user.getRole(),
                accessGroupService.permissionsFor(user.getAccessGroup()),
                user.businessUnitIds());
        return new SsoLoginResponseDto(
                token,
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getExtension()
        );
    }

    /** Troca o code por access_token e consulta o Graph — I/O de rede puro, fora de qualquer transação de banco. */
    private String[] exchangeCodeForIdentity(SsoConfiguration cfg, String tokenEndpoint, String code, String effectiveRedirect) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", cfg.getClientId() != null ? cfg.getClientId() : "");
        body.add("client_secret", cfg.getClientSecret() != null ? cfg.getClientSecret() : "");
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", effectiveRedirect);

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(body, headers);

        String email = null;
        String displayName = null;
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(tokenEndpoint, requestEntity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String accessToken = (String) response.getBody().get("access_token");
                if (accessToken != null) {
                    HttpHeaders graphHeaders = new HttpHeaders();
                    graphHeaders.setBearerAuth(accessToken);
                    HttpEntity<?> graphEntity = new HttpEntity<>(graphHeaders);
                    ResponseEntity<Map> userResponse = restTemplate.exchange(
                            "https://graph.microsoft.com/v1.0/me",
                            HttpMethod.GET,
                            graphEntity,
                            Map.class
                    );
                    if (userResponse.getStatusCode().is2xxSuccessful() && userResponse.getBody() != null) {
                        Map userData = userResponse.getBody();
                        email = (String) userData.get("mail");
                        if (email == null || email.isBlank()) {
                            email = (String) userData.get("userPrincipalName");
                        }
                        displayName = (String) userData.get("displayName");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Falha ao autenticar código com Microsoft Entra ID: {}", e.getClass().getSimpleName());
            throw new SecurityException("Falha na validação de credenciais junto ao Microsoft Entra ID.");
        }
        return new String[]{email, displayName};
    }

    private AppUser resolveOrProvisionUser(SsoConfiguration cfg, String username, String displayName) {
        Optional<AppUser> userOpt = userRepository.findByUsernameIgnoreCase(username);

        if (userOpt.isPresent()) {
            AppUser user = userOpt.get();
            // Impede sequestro de conta local: só uma conta explicitamente vinculada ao SSO pode
            // ser autenticada por este fluxo, mesmo racional do adLinked no login via AD.
            if (!Boolean.TRUE.equals(user.getSsoLinked())) {
                throw new SecurityException(
                        "Esta conta não está vinculada ao login corporativo (SSO). Use a senha local.");
            }
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                throw new IllegalStateException("Conta de usuário desativada.");
            }
            if (user.hasExpiredAccess()) {
                throw new IllegalStateException("Acesso expirado.");
            }
            if (Boolean.TRUE.equals(user.getTotpEnabled())) {
                // Decisão deliberada: SSO não substitui o 2FA local — quem ativou TOTP precisa
                // continuar entrando pelo login com senha (que já exige o segundo fator).
                throw new SecurityException(
                        "Esta conta tem autenticação em duas etapas ativada — entre com usuário e senha.");
            }
            return user;
        }

        if (!Boolean.TRUE.equals(cfg.getAutoProvisionUsers())) {
            throw new SecurityException("Usuário não possui conta prévia e provisionamento automático está desativado.");
        }
        int nextExt = userRepository.findNextExtension(9010);
        var defaultGroup = cfg.getDefaultAccessGroup() != null
                ? cfg.getDefaultAccessGroup()
                : accessGroupRepository.findById(2)
                        .orElseThrow(() -> new IllegalStateException(
                                "Nenhum grupo de acesso padrão configurado para provisionamento via SSO"));
        AppUser user = AppUser.builder()
                .username(username)
                .displayName((displayName != null && !displayName.isBlank()) ? displayName : username)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .extension(nextExt)
                .role("USER")
                .accessGroup(defaultGroup)
                .isActive(true)
                .accessIndeterminate(true)
                .firstLoginCompleted(false)
                .ssoLinked(true)
                .build();
        user = userRepository.save(user);
        log.info("Novo usuário provisionado automaticamente via SSO Microsoft Entra: {} (Ramal {})", username, nextExt);
        return user;
    }

    private boolean consumeValidState(String state) {
        cleanupExpiredStates();
        if (state == null || state.isBlank()) {
            return false;
        }
        Instant issuedAt = pendingStates.remove(state);
        return issuedAt != null && issuedAt.isAfter(Instant.now().minusSeconds(STATE_TTL_SECONDS));
    }

    private void cleanupExpiredStates() {
        Instant cutoff = Instant.now().minusSeconds(STATE_TTL_SECONDS);
        pendingStates.values().removeIf(issuedAt -> issuedAt.isBefore(cutoff));
    }

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
        if (req.defaultAccessGroupId() != null) {
            var group = accessGroupRepository.findById(req.defaultAccessGroupId())
                    .orElseThrow(() -> new IllegalArgumentException("Grupo de acesso não encontrado: " + req.defaultAccessGroupId()));
            cfg.setDefaultAccessGroup(group);
        }
        cfg.setUpdatedAt(Instant.now());

        return ssoConfigRepository.save(cfg);
    }

    public record SsoPublicConfigDto(boolean enabled, String displayName, String provider) {}

    public record SsoLoginResponseDto(String token, Integer id, String username, String displayName, Integer extension) {}

    public record SsoConfigUpdateRequest(
            String displayName,
            String clientId,
            String clientSecret,
            String tenantId,
            String redirectUri,
            Boolean autoProvisionUsers,
            Boolean isActive,
            Integer defaultAccessGroupId
    ) {}
}
