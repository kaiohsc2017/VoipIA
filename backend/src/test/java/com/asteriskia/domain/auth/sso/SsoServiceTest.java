package com.asteriskia.domain.auth.sso;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.accessgroup.AccessGroup;
import com.asteriskia.domain.accessgroup.AccessGroupRepository;
import com.asteriskia.domain.accessgroup.AccessGroupService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class SsoServiceTest {

    @Mock private SsoConfigurationRepository ssoConfigRepository;
    @Mock private AppUserRepository userRepository;
    @Mock private AccessGroupRepository accessGroupRepository;
    @Mock private AccessGroupService accessGroupService;
    @Mock private JwtService jwtService;
    @Mock private PasswordEncoder passwordEncoder;

    private SsoService ssoService;

    @BeforeEach
    void setUp() {
        ssoService = new SsoService(
                ssoConfigRepository, userRepository, accessGroupRepository, accessGroupService,
                jwtService, passwordEncoder);
    }

    private SsoConfiguration activeConfig() {
        return SsoConfiguration.builder()
                .providerName("MICROSOFT_ENTRA")
                .displayName("Microsoft 365 / Entra ID")
                .clientId("test-client-id")
                .clientSecret("test-secret")
                .tenantId("test-tenant")
                .redirectUri("https://app.voiphash.com.br/login")
                .autoProvisionUsers(true)
                .isActive(true)
                .build();
    }

    @Test
    void getPublicConfig_active_returnsEnabled() {
        when(ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA"))
                .thenReturn(Optional.of(activeConfig()));

        var config = ssoService.getPublicConfig();

        assertTrue(config.enabled());
        assertEquals("Microsoft 365 / Entra ID", config.displayName());
    }

    @Test
    void getPublicConfig_inactive_returnsDisabled() {
        when(ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA")).thenReturn(Optional.empty());

        var config = ssoService.getPublicConfig();

        assertFalse(config.enabled());
    }

    @Test
    void buildAuthorizeUrl_throws_whenProviderDisabled() {
        when(ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> ssoService.buildAuthorizeUrl("https://x/login"));
    }

    @Test
    void buildAuthorizeUrl_ignoresCallerRedirect_usesConfigured() {
        when(ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA"))
                .thenReturn(Optional.of(activeConfig()));

        String url = ssoService.buildAuthorizeUrl("https://atacante.example/steal");

        assertFalse(url.contains("atacante.example"));
        assertTrue(url.contains("app.voiphash.com.br"));
        assertTrue(url.contains("state="));
    }

    @Test
    void processSsoLoginWithCode_rejectsMissingCode() {
        assertThrows(SecurityException.class,
                () -> ssoService.processSsoLoginWithCode(null, "any-state", null));
    }

    @Test
    void processSsoLoginWithCode_rejectsUnknownOrExpiredState() {
        assertThrows(SecurityException.class,
                () -> ssoService.processSsoLoginWithCode("some-code", "state-nunca-emitido", null));
    }

    @Test
    void processSsoLoginWithCode_rejectsWhenProviderNotActive() {
        // Emite um state real (provedor ativo no momento da autorização)...
        when(ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA"))
                .thenReturn(Optional.of(activeConfig()));
        String authorizeUrl = ssoService.buildAuthorizeUrl("https://app.voiphash.com.br/login");
        String state = authorizeUrl.replaceAll(".*state=([^&]+).*", "$1");

        // ...mas o provedor foi desativado antes do callback ser processado.
        when(ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA")).thenReturn(Optional.of(
                activeConfig().toBuilder().isActive(false).build()));

        assertThrows(SecurityException.class,
                () -> ssoService.processSsoLoginWithCode("some-code", state, null));
    }

    @Test
    void resolveOrProvisionUser_rejectsAccountNotSsoLinked() {
        AppUser existing = AppUser.builder()
                .id(1)
                .username("carlos.silva@empresa.com.br")
                .isActive(true)
                .ssoLinked(false)
                .build();
        when(userRepository.findByUsernameIgnoreCase("carlos.silva@empresa.com.br"))
                .thenReturn(Optional.of(existing));

        var ex = assertThrows(SecurityException.class, () ->
                invokeResolveOrProvision(activeConfig(), "carlos.silva@empresa.com.br", "Carlos Silva"));
        assertTrue(ex.getMessage().contains("SSO"));
    }

    @Test
    void resolveOrProvisionUser_rejectsExpiredAccess() {
        AppUser existing = AppUser.builder()
                .id(1)
                .username("carlos.silva@empresa.com.br")
                .isActive(true)
                .ssoLinked(true)
                .accessIndeterminate(false)
                .accessExpiresAt(LocalDate.now().minusDays(1))
                .build();
        when(userRepository.findByUsernameIgnoreCase("carlos.silva@empresa.com.br"))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () ->
                invokeResolveOrProvision(activeConfig(), "carlos.silva@empresa.com.br", "Carlos Silva"));
    }

    @Test
    void resolveOrProvisionUser_rejectsTotpEnabled() {
        AppUser existing = AppUser.builder()
                .id(1)
                .username("carlos.silva@empresa.com.br")
                .isActive(true)
                .ssoLinked(true)
                .accessIndeterminate(true)
                .totpEnabled(true)
                .build();
        when(userRepository.findByUsernameIgnoreCase("carlos.silva@empresa.com.br"))
                .thenReturn(Optional.of(existing));

        assertThrows(SecurityException.class, () ->
                invokeResolveOrProvision(activeConfig(), "carlos.silva@empresa.com.br", "Carlos Silva"));
    }

    @Test
    void resolveOrProvisionUser_acceptsLinkedActiveUser() throws Exception {
        AppUser existing = AppUser.builder()
                .id(1)
                .username("carlos.silva@empresa.com.br")
                .isActive(true)
                .ssoLinked(true)
                .accessIndeterminate(true)
                .totpEnabled(false)
                .build();
        when(userRepository.findByUsernameIgnoreCase("carlos.silva@empresa.com.br"))
                .thenReturn(Optional.of(existing));

        AppUser result = invokeResolveOrProvision(activeConfig(), "carlos.silva@empresa.com.br", "Carlos Silva");

        assertEquals(existing, result);
    }

    @Test
    void resolveOrProvisionUser_newUser_usesConfiguredDefaultGroup() throws Exception {
        AccessGroup configuredGroup = AccessGroup.builder().id(7).name("Grupo SSO").build();
        SsoConfiguration cfg = activeConfig().toBuilder().defaultAccessGroup(configuredGroup).build();

        when(userRepository.findByUsernameIgnoreCase("novo@empresa.com.br")).thenReturn(Optional.empty());
        when(userRepository.findNextExtension(9010)).thenReturn(9020);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(userRepository.save(any(AppUser.class))).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            u.setId(50);
            return u;
        });

        AppUser result = invokeResolveOrProvision(cfg, "novo@empresa.com.br", "Novo Usuario");

        assertEquals(configuredGroup, result.getAccessGroup());
        assertTrue(result.getSsoLinked());
        verify(accessGroupRepository, never()).findById(anyInt());
    }

    @Test
    void resolveOrProvisionUser_newUser_withoutAutoProvision_throws() {
        SsoConfiguration cfg = activeConfig().toBuilder().autoProvisionUsers(false).build();
        when(userRepository.findByUsernameIgnoreCase("novo@empresa.com.br")).thenReturn(Optional.empty());

        assertThrows(SecurityException.class, () -> invokeResolveOrProvision(cfg, "novo@empresa.com.br", "Novo"));
    }

    @Test
    void updateAdminConfig_resolvesDefaultAccessGroupById() {
        SsoConfiguration cfg = activeConfig();
        AccessGroup group = AccessGroup.builder().id(3).name("Supervisores").build();
        when(ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA")).thenReturn(Optional.of(cfg));
        when(accessGroupRepository.findById(3)).thenReturn(Optional.of(group));
        when(ssoConfigRepository.save(any(SsoConfiguration.class))).thenAnswer(i -> i.getArgument(0));

        var updated = ssoService.updateAdminConfig(new SsoService.SsoConfigUpdateRequest(
                null, null, null, null, null, null, null, 3));

        assertEquals(group, updated.getDefaultAccessGroup());
    }

    // --- helpers de reflexão para exercitar o método privado de resolução/provisionamento ---

    private AppUser invokeResolveOrProvision(SsoConfiguration cfg, String username, String displayName) throws Exception {
        var method = SsoService.class.getDeclaredMethod(
                "resolveOrProvisionUser", SsoConfiguration.class, String.class, String.class);
        method.setAccessible(true);
        try {
            return (AppUser) method.invoke(ssoService, cfg, username, displayName);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }
}
