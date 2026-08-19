package com.asteriskia.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.asteriskia.domain.accessgroup.AccessGroupService;
import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import com.asteriskia.integration.ad.AdLdapConfig;
import com.asteriskia.integration.ad.AdUser;
import com.asteriskia.integration.ad.AdUserService;
import com.asteriskia.integration.ad.LdapClient;
import com.asteriskia.integration.ad.LdapUserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** AuthControllerTest — Testa os fluxos de login: normal, 2FA e credenciais inválidas. */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private JwtService jwtService;

    @MockBean private AppUserRepository userRepo;

    @MockBean private AuditService auditService;

    @MockBean private RefreshTokenService refreshTokenService;

    @MockBean private AccessGroupService accessGroupService;

    @MockBean private LdapClient ldapClient;

    @MockBean private AdUserService adUserService;

    private static final AdLdapConfig AD_DISABLED =
            new AdLdapConfig(false, "", 636, true, "", "", "", true, 2);

    @BeforeEach
    void setUpAdDisabledByDefault() {
        // A maioria dos testes de login local não deve nem tocar no AD — só os testes de AD
        // explícitos abaixo sobrescrevem este stub para habilitá-lo.
        when(ldapClient.currentConfig()).thenReturn(AD_DISABLED);
    }

    // ─── Login normal (sem 2FA) ───────────────────────────────────────────────

    @Test
    void login_credenciaisValidas_sem2FA_deveRetornarToken() throws Exception {
        AppUser user =
                AppUser.builder()
                        .username("kaio")
                        .passwordHash(
                                new org.springframework.security.crypto.bcrypt
                                                .BCryptPasswordEncoder()
                                        .encode("senha123"))
                        .displayName("Kaio")
                        .extension(9001)
                        .isActive(true)
                        .totpEnabled(false)
                        .build();

        when(userRepo.findByUsernameAndIsActiveTrue("kaio")).thenReturn(Optional.of(user));
        when(accessGroupService.permissionsFor(any())).thenReturn(java.util.Map.of());
        when(jwtService.generateToken(eq("kaio"), eq(9001), any(), any(), eq(java.util.Set.of())))
                .thenReturn("jwt-token-mock");
        when(refreshTokenService.generateRefreshToken("kaio")).thenReturn("refresh-token-mock");
        doNothing().when(auditService).logAs(any(), any(), any(), any(), anyBoolean());

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new LoginRequest("kaio", "senha123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token-mock"))
                .andExpect(jsonPath("$.type").value("Bearer"));
    }

    @Test
    void login_com2FA_deveRetornarTempToken() throws Exception {
        AppUser user =
                AppUser.builder()
                        .username("kaio2fa")
                        .passwordHash(
                                new org.springframework.security.crypto.bcrypt
                                                .BCryptPasswordEncoder()
                                        .encode("senha123"))
                        .displayName("Kaio 2FA")
                        .extension(9002)
                        .isActive(true)
                        .totpEnabled(true)
                        .totpSecret("BASE32SECRET")
                        .build();

        when(userRepo.findByUsernameAndIsActiveTrue("kaio2fa")).thenReturn(Optional.of(user));
        when(jwtService.generateTempToken("kaio2fa")).thenReturn("temp-token-mock");
        doNothing().when(auditService).logAs(any(), any(), any(), any(), anyBoolean());

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new LoginRequest("kaio2fa", "senha123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresTotp").value(true))
                .andExpect(jsonPath("$.tempToken").value("temp-token-mock"));
    }

    @Test
    void login_credenciaisInvalidas_deveRetornar401() throws Exception {
        when(userRepo.findByUsernameAndIsActiveTrue(anyString())).thenReturn(Optional.empty());
        doNothing().when(auditService).logAs(any(), any(), any(), any(), anyBoolean());

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new LoginRequest("errado", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    // ─── Refresh — regressão de segurança ─────────────────────────────────────
    // Achado do security-reviewer: um usuário desativado/removido que ainda
    // segure um refresh token válido (até 7 dias) não pode ganhar a claim
    // "perm" completa do grupo Administradores — role e perms precisam ficar
    // sincronizados no mesmo branch "default" (USER / sem permissão nenhuma).

    @Test
    void refresh_usuarioDesativadoOuRemovido_naoDeveGanharPermissoesDeAdmin() throws Exception {
        String username = "usuario_desativado";

        RefreshToken storedToken =
                RefreshToken.builder()
                        .id(1L)
                        .username(username)
                        .tokenHash("hash-irrelevante")
                        .revoked(false)
                        .build();

        when(refreshTokenService.validateRefreshToken("refresh-valido"))
                .thenReturn(Optional.of(storedToken));
        // findByUsernameAndIsActiveTrue vazio: simula usuário desativado (soft
        // delete) ou excluído — cai no branch "default" de refresh().
        when(userRepo.findByUsernameAndIsActiveTrue(username)).thenReturn(Optional.empty());
        when(refreshTokenService.generateRefreshToken(username)).thenReturn("novo-refresh-token");
        when(jwtService.generateToken(
                        eq(username), eq(9001), eq("USER"), any(), eq(java.util.Set.of())))
                .thenReturn("novo-jwt-mock");

        mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/auth/refresh")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "voipia_refresh_token", "refresh-valido")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("novo-jwt-mock"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> permsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jwtService)
                .generateToken(
                        eq(username),
                        eq(9001),
                        eq("USER"),
                        permsCaptor.capture(),
                        eq(java.util.Set.of()));

        // O bug corrigido: perms vinha pré-inicializado com o grupo Administradores
        // (leitura+escrita em todos os 19 recursos) mesmo quando role="USER".
        assertThat(permsCaptor.getValue()).isEmpty();
    }

    // ─── Fallback AD/LDAP (módulo Call Center, Fase 1) ────────────────────────

    private static final AdLdapConfig AD_ENABLED =
            new AdLdapConfig(true, "dc.empresa.local", 636, true, "DC=empresa,DC=local", "svc", "pw", true, 2);

    @Test
    void login_usuarioNaoLocal_bindAdOk_deveProvisionarNovoUsuario() throws Exception {
        LdapUserAttributes attrs =
                new LdapUserAttributes(
                        "novo.ad", "Novo Usuário AD", "TI", "Matriz", "Analista",
                        List.of("CN=Suporte,OU=Grupos,DC=empresa,DC=local"), null, "novo@empresa.com", null, null);

        when(userRepo.findByUsernameAndIsActiveTrue("novo.ad")).thenReturn(Optional.empty());
        when(ldapClient.currentConfig()).thenReturn(AD_ENABLED);
        when(ldapClient.authenticate("novo.ad", "senhaAd123")).thenReturn(Optional.of(attrs));
        when(adUserService.upsertMirror(attrs)).thenReturn(new AdUser());
        when(userRepo.findByUsername("novo.ad")).thenReturn(Optional.empty());
        when(adUserService.resolveAccessGroup(attrs.memberOf(), 2))
                .thenReturn(com.asteriskia.domain.accessgroup.AccessGroup.builder().id(2).name("Usuários").build());
        when(userRepo.findNextExtension(9001)).thenReturn(9010);
        when(userRepo.save(any(AppUser.class)))
                .thenAnswer(
                        invocation -> {
                            AppUser u = invocation.getArgument(0);
                            u.setId(99);
                            return u;
                        });
        when(accessGroupService.permissionsFor(any())).thenReturn(Map.of());
        when(jwtService.generateToken(eq("novo.ad"), eq(9010), any(), any(), any()))
                .thenReturn("jwt-ad-mock");
        when(refreshTokenService.generateRefreshToken("novo.ad")).thenReturn("refresh-ad-mock");

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new LoginRequest("novo.ad", "senhaAd123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-ad-mock"))
                .andExpect(jsonPath("$.extension").value(9010));

        verify(userRepo).save(argThat(u -> "novo.ad".equals(u.getUsername()) && u.getIsActive()));
    }

    @Test
    void login_bindAdOk_contaLocalNaoVinculadaAoAd_naoDeveAutenticar() throws Exception {
        // CRÍTICO (achado de segurança): uma conta local "nativa" (criada pela tela Usuários, sem
        // adLinked) não pode ser autenticada só porque alguém sabe a senha AD do mesmo username.
        AppUser contaNativa =
                AppUser.builder()
                        .username("admin.local")
                        .isActive(true)
                        .adLinked(false)
                        .extension(9030)
                        .build();
        LdapUserAttributes attrs =
                new LdapUserAttributes("admin.local", "Admin Local", null, null, null, List.of(), null, null, null, null);

        when(userRepo.findByUsernameAndIsActiveTrue("admin.local")).thenReturn(Optional.empty());
        when(ldapClient.currentConfig()).thenReturn(AD_ENABLED);
        when(ldapClient.authenticate("admin.local", "senhaDoADdeAlguem")).thenReturn(Optional.of(attrs));
        when(adUserService.upsertMirror(attrs)).thenReturn(new AdUser());
        when(userRepo.findByUsername("admin.local")).thenReturn(Optional.of(contaNativa));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new LoginRequest("admin.local", "senhaDoADdeAlguem"))))
                .andExpect(status().isUnauthorized());

        verify(userRepo, never()).save(any());
        verify(jwtService, never()).generateToken(eq("admin.local"), any(), any(), any(), any());
    }

    @Test
    void login_bindAdOk_contaLocalJaVinculadaAoAd_deveAutenticarNormalmente() throws Exception {
        AppUser vinculada =
                AppUser.builder()
                        .username("ja.vinculado")
                        .isActive(true)
                        .adLinked(true)
                        .extension(9040)
                        .role("USER")
                        .build();
        LdapUserAttributes attrs =
                new LdapUserAttributes("ja.vinculado", "Já Vinculado", null, null, null, List.of(), null, null, null, null);

        when(userRepo.findByUsernameAndIsActiveTrue("ja.vinculado")).thenReturn(Optional.empty());
        when(ldapClient.currentConfig()).thenReturn(AD_ENABLED);
        when(ldapClient.authenticate("ja.vinculado", "senhaAd")).thenReturn(Optional.of(attrs));
        when(adUserService.upsertMirror(attrs)).thenReturn(new AdUser());
        when(userRepo.findByUsername("ja.vinculado")).thenReturn(Optional.of(vinculada));
        when(accessGroupService.permissionsFor(any())).thenReturn(Map.of());
        when(jwtService.generateToken(eq("ja.vinculado"), eq(9040), any(), any(), any()))
                .thenReturn("jwt-ad-existente-mock");
        when(refreshTokenService.generateRefreshToken("ja.vinculado")).thenReturn("refresh-mock");

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new LoginRequest("ja.vinculado", "senhaAd"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-ad-existente-mock"));

        verify(userRepo, never()).save(any());
    }

    @Test
    void login_bindAdOk_masContaLocalDesativada_naoDeveContornarDesativacao() throws Exception {
        AppUser desativado =
                AppUser.builder().username("des.ad").isActive(false).extension(9020).build();
        LdapUserAttributes attrs =
                new LdapUserAttributes("des.ad", "Desativado", null, null, null, List.of(), null, null, null, null);

        when(userRepo.findByUsernameAndIsActiveTrue("des.ad")).thenReturn(Optional.empty());
        when(ldapClient.currentConfig()).thenReturn(AD_ENABLED);
        when(ldapClient.authenticate("des.ad", "qualquer")).thenReturn(Optional.of(attrs));
        when(adUserService.upsertMirror(attrs)).thenReturn(new AdUser());
        when(userRepo.findByUsername("des.ad")).thenReturn(Optional.of(desativado));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new LoginRequest("des.ad", "qualquer"))))
                .andExpect(status().isUnauthorized());

        verify(userRepo, never()).save(any());
    }

    @Test
    void login_adDesabilitado_naoDeveChamarLdapClientAuthenticate() throws Exception {
        when(userRepo.findByUsernameAndIsActiveTrue(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new LoginRequest("qualquer", "qualquer"))))
                .andExpect(status().isUnauthorized());

        verify(ldapClient, never()).authenticate(any(), any());
    }
}
