package com.asteriskia.domain.security;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.asteriskia.config.JwtService;
import com.asteriskia.config.RateLimitFilter;
import com.asteriskia.domain.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

/**
 * SecurityControllerTest — testes de caracterização (fase 0/7 da refatoração).
 *
 * <p>Objetivo: travar o comportamento ATUAL do controller (validações, mensagens de erro, chamadas
 * aos colaboradores) antes de extrair/mover código na fase 1. Não avalia se o comportamento é
 * "ideal" — só garante que a refatoração não o altera silenciosamente.
 */
@WebMvcTest(SecurityController.class)
@Import({SecurityListsRepository.class, SecurityJailService.class, AsteriskLogClient.class})
class SecurityControllerTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private AuditService auditService;

    @MockBean private RestTemplate restTemplate;

    @MockBean private FailToBanClient f2b;

    @MockBean private JailConfigRepository jailConfigRepo;

    @MockBean private AsteriskAclService aclService;

    @MockBean private JwtService jwtService;

    @MockBean private RateLimitFilter rateLimitFilter;

    /**
     * RateLimitFilter é um @Component que implementa Filter — o @WebMvcTest o registra
     * automaticamente na cadeia do MockMvc. Como mock, doFilter() vira um no-op que nunca chama
     * chain.doFilter(), engolindo toda requisição antes do controller. Configura o mock como
     * pass-through para não mascarar o comportamento real do SecurityController nestes testes de
     * caracterização.
     */
    @BeforeEach
    void passThroughRateLimitFilter() throws Exception {
        doAnswer(
                        invocation -> {
                            ServletRequest req = invocation.getArgument(0);
                            ServletResponse res = invocation.getArgument(1);
                            FilterChain chain = invocation.getArgument(2);
                            chain.doFilter(req, res);
                            return null;
                        })
                .when(rateLimitFilter)
                .doFilter(any(), any(), any());
    }

    // ── Status ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void status_devolveResumoComJailsEWhitelist() throws Exception {
        when(f2b.isRunning()).thenReturn(true);
        when(jailConfigRepo.parseJailConfig(anyString())).thenReturn(Map.of());
        when(f2b.exec(eq("status"), anyString())).thenReturn("Status");
        when(f2b.parseBannedCount(anyString())).thenReturn(0);
        when(f2b.parseTotalFailed(anyString())).thenReturn(0);

        mockMvc.perform(get("/api/v1/security/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fail2banRunning").value(true))
                .andExpect(jsonPath("$.jails.length()").value(3))
                .andExpect(jsonPath("$.whitelist").isArray());
    }

    // ── Jails ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void jailDetail_jailDesconhecido_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/security/jails/inexistente"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Jail desconhecido: inexistente"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateJail_jailDesconhecido_devolve400() throws Exception {
        mockMvc.perform(
                        put("/api/v1/security/jails/inexistente")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Jail desconhecido: inexistente"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateJail_banactionForaDaAllowlist_devolve400() throws Exception {
        mockMvc.perform(
                        put("/api/v1/security/jails/asterisk-auth")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"banaction\":\"rm-rf-tudo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(org.hamcrest.Matchers.containsString("banaction inválido")));

        verify(jailConfigRepo, never()).updateJailParam(anyString(), anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateJail_valoresValidos_atualizaEDaReload() throws Exception {
        when(f2b.exec("reload", "asterisk-auth")).thenReturn("OK");

        mockMvc.perform(
                        put("/api/v1/security/jails/asterisk-auth")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"maxretry\":10,\"banaction\":\"nftables-multiport\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reload").value("OK"));

        verify(jailConfigRepo).updateJailParam("asterisk-auth", "maxretry", "10");
        verify(jailConfigRepo).updateJailParam("asterisk-auth", "banaction", "nftables-multiport");
        verify(auditService).log(any(), eq("SECURITY_JAIL_UPDATE"), anyString(), eq(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void enableJail_jailDesconhecido_devolve400() throws Exception {
        mockMvc.perform(post("/api/v1/security/jails/inexistente/enable").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // ── Ban / Unban ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void ban_semIp_devolve400() throws Exception {
        mockMvc.perform(
                        post("/api/v1/security/ban")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("IP obrigatório"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ban_ipInvalido_devolve400() throws Exception {
        mockMvc.perform(
                        post("/api/v1/security/ban")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"ip\":\"nao-e-um-ip\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("IP inválido: nao-e-um-ip"));

        verifyNoInteractions(aclService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ban_ipValido_chamaFail2banEAcl() throws Exception {
        when(f2b.exec("set", "asterisk-auth", "banip", "1.2.3.4")).thenReturn("OK");

        mockMvc.perform(
                        post("/api/v1/security/ban")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"ip\":\"1.2.3.4\"}"))
                .andExpect(status().isOk());

        verify(aclService).addToAsteriskAcl("1.2.3.4");
        verify(auditService).log(any(), eq("SECURITY_BAN"), anyString(), eq(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unban_semJailEspecifico_desbaneEmTodasAsJails() throws Exception {
        mockMvc.perform(delete("/api/v1/security/ban/1.2.3.4").with(csrf()))
                .andExpect(status().isOk());

        verify(f2b).exec("set", "asterisk-auth", "unbanip", "1.2.3.4");
        verify(f2b).exec("set", "asterisk-scan", "unbanip", "1.2.3.4");
        verify(f2b).exec("set", "asterisk-flood", "unbanip", "1.2.3.4");
        verify(aclService).removeFromAsteriskAcl("1.2.3.4");
    }

    // ── Whitelist ─────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void whitelist_semArquivo_devolveDefaultDaAsterisk() throws Exception {
        mockMvc.perform(get("/api/v1/security/whitelist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("127.0.0.1/8"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addWhitelist_ipInvalido_devolve400() throws Exception {
        mockMvc.perform(
                        post("/api/v1/security/whitelist")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"ip\":\"invalido\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addWhitelist_ipValido_devolve200EGravaAuditoria() throws Exception {
        mockMvc.perform(
                        post("/api/v1/security/whitelist")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"ip\":\"8.8.8.8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("8.8.8.8 adicionado à lista branca."));

        verify(auditService).log(any(), eq("SECURITY_WHITELIST_ADD"), eq("8.8.8.8"), eq(true));
    }

    // ── Lockdown ──────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void lockdownStatus_inativo_devolveMensagemDeModoNormal() throws Exception {
        when(aclService.isLockdownActive()).thenReturn(false);

        mockMvc.perform(get("/api/v1/security/lockdown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(
                        jsonPath("$.description")
                                .value("Modo normal — fail2ban monitora e bloqueia ameaças"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void enableLockdown_aplicaIptablesAclEFlag() throws Exception {
        mockMvc.perform(post("/api/v1/security/lockdown/enable").with(csrf()))
                .andExpect(status().isOk());

        verify(aclService).applyLockdownIptables(anyList());
        verify(aclService).applyLockdownAcl(anyList());
        verify(aclService).writeLockdownFlag(true);
        verify(auditService).log(any(), eq("SECURITY_LOCKDOWN_ENABLE"), anyString(), eq(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void disableLockdown_removeIptablesERestauraAclPermissiva() throws Exception {
        mockMvc.perform(post("/api/v1/security/lockdown/disable").with(csrf()))
                .andExpect(status().isOk());

        verify(aclService).removeLockdownIptables();
        verify(aclService).restorePermissiveAcl();
        verify(aclService).writeLockdownFlag(false);
        verify(auditService).log(any(), eq("SECURITY_LOCKDOWN_DISABLE"), anyString(), eq(true));
    }

    // ── Teste de regex do log do Asterisk ───────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void testRegex_semRegex_devolve400() throws Exception {
        mockMvc.perform(
                        post("/api/v1/security/test-regex")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Regex obrigatória."));
    }

    @SuppressWarnings("unchecked")
    @Test
    @WithMockUser(roles = "ADMIN")
    void testRegex_regexComSintaxeInvalida_devolve400() throws Exception {
        // tailAsteriskLog() (docker-helper) é chamado ANTES de Pattern.compile() no
        // controller — precisa responder com sucesso para a validação da regex ser
        // alcançada (característica real do fluxo, não do teste).
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("lines", List.of("linha de log"))));

        mockMvc.perform(
                        post("/api/v1/security/test-regex")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"regex\":\"[\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(org.hamcrest.Matchers.containsString("Regex inválida")));
    }

    @SuppressWarnings("unchecked")
    @Test
    @WithMockUser(roles = "ADMIN")
    void testRegex_regexValida_filtraLinhasDoLogViaDockerHelper() throws Exception {
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(
                        ResponseEntity.ok(
                                Map.of(
                                        "lines",
                                        List.of(
                                                "registered SIP endpoint 9001",
                                                "authentication failure de 1.2.3.4"))));

        mockMvc.perform(
                        post("/api/v1/security/test-regex")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"regex\":\"authentication failure\",\"lines\":\"200\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.tested").value(2));
    }
}
