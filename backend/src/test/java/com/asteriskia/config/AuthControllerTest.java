package com.asteriskia.config;

import com.asteriskia.domain.accessgroup.AccessGroupService;
import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthControllerTest — Testa os fluxos de login: normal, 2FA e credenciais inválidas.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private AppUserRepository userRepo;

    @MockBean
    private AuditService auditService;

    @MockBean
    private RefreshTokenService refreshTokenService;

    @MockBean
    private AccessGroupService accessGroupService;

    // ─── Login normal (sem 2FA) ───────────────────────────────────────────────

    @Test
    void login_credenciaisValidas_sem2FA_deveRetornarToken() throws Exception {
        AppUser user = AppUser.builder()
                .username("kaio")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("senha123"))
                .displayName("Kaio")
                .extension(9001)
                .isActive(true)
                .totpEnabled(false)
                .build();

        when(userRepo.findByUsernameAndIsActiveTrue("kaio")).thenReturn(Optional.of(user));
        when(accessGroupService.permissionsFor(any())).thenReturn(java.util.Map.of());
        when(jwtService.generateToken(eq("kaio"), eq(9001), any(), any())).thenReturn("jwt-token-mock");
        when(refreshTokenService.generateRefreshToken("kaio")).thenReturn("refresh-token-mock");
        doNothing().when(auditService).logAs(any(), any(), any(), any(), anyBoolean());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("kaio", "senha123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token-mock"))
                .andExpect(jsonPath("$.type").value("Bearer"));
    }

    @Test
    void login_com2FA_deveRetornarTempToken() throws Exception {
        AppUser user = AppUser.builder()
                .username("kaio2fa")
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
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

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("kaio2fa", "senha123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresTotp").value(true))
                .andExpect(jsonPath("$.tempToken").value("temp-token-mock"));
    }

    @Test
    void login_credenciaisInvalidas_deveRetornar401() throws Exception {
        when(userRepo.findByUsernameAndIsActiveTrue(anyString())).thenReturn(Optional.empty());
        doNothing().when(auditService).logAs(any(), any(), any(), any(), anyBoolean());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("errado", "wrong"))))
                .andExpect(status().isUnauthorized());
    }
}
