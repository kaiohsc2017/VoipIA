package com.asteriskia.domain.logs;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.asteriskia.config.JwtService;
import com.asteriskia.config.RateLimitFilter;
import com.asteriskia.domain.audit.AuditService;
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
 * LogsControllerTest — testes de caracterização (fase 0/4 da refatoração).
 *
 * <p>Cobre os endpoints síncronos e determinísticos (docker snapshot/history/download, asterisk
 * snapshot/download/status). Os endpoints SSE (/docker/stream, /asterisk/stream) usam threads
 * virtuais e streaming assíncrono — fora de escopo aqui por risco de flakiness em um teste de
 * caracterização; ficam para uma verificação manual/E2E quando o controller for de fato refatorado.
 */
@WebMvcTest(LogsController.class)
@Import({DockerHelperClient.class, AsteriskAmiClient.class})
class LogsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private AuditService auditService;

    @MockBean private RestTemplate restTemplate;

    @MockBean private JwtService jwtService;

    @MockBean private RateLimitFilter rateLimitFilter;

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

    @SuppressWarnings("unchecked")
    private void mockHelperLines(List<String> lines) {
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("lines", lines)));
    }

    // ── Docker snapshot ───────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void dockerSnapshot_semServicos_consultaTodosOsContainersDoStack() throws Exception {
        mockHelperLines(List.of("2026-07-13T10:00:00.000Z linha normal"));

        mockMvc.perform(get("/api/v1/logs/docker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(7)); // 1 linha x 7 serviços de ALL_SERVICES

        verify(restTemplate, times(7))
                .exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void dockerSnapshot_servicoSemPrefixo_recebePrefixoAsteriskia() throws Exception {
        mockHelperLines(List.of());

        mockMvc.perform(get("/api/v1/logs/docker?services=backend")).andExpect(status().isOk());

        verify(restTemplate)
                .exchange(
                        contains("/logs/voipia-backend"),
                        eq(HttpMethod.GET),
                        any(HttpEntity.class),
                        eq(Map.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void dockerSnapshot_filtraPorLevel() throws Exception {
        // Docker real usa timestamp com precisão de nanossegundos (30 chars) — o
        // parser do controller (parseLine) assume esse formato fixo (substring(31)).
        mockHelperLines(
                List.of(
                        "2026-07-13T10:00:00.123456789Z tudo normal por aqui",
                        "2026-07-13T10:00:01.123456789Z ERROR algo quebrou"));

        mockMvc.perform(get("/api/v1/logs/docker?services=backend&levels=ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entries[0].level").value("ERROR"));
    }

    // ── Docker history ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void dockerHistory_devolveChartPorHora() throws Exception {
        mockHelperLines(List.of("2026-07-13T14:00:00.000Z tudo bem"));

        mockMvc.perform(get("/api/v1/logs/docker/history?services=backend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chart.byHour").isMap());
    }

    // ── Docker download ───────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void dockerDownload_devolveAnexoTextoEGravaAuditoria() throws Exception {
        mockHelperLines(List.of("linha de log"));

        mockMvc.perform(get("/api/v1/logs/docker/download?services=backend"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.startsWith(
                                                "attachment; filename=docker-")));

        verify(auditService).log(any(), eq("LOGS_DOWNLOAD"), anyString(), eq(true));
    }

    // ── Asterisk snapshot ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void asteriskSnapshot_categorizaLinhasDeRegistro() throws Exception {
        // Comportamento real atual (detectAsteriskCategory): a checagem de "ICE"
        // (WebRTC/DTLS) roda antes da de "PJSIP" e usa contains() simples — "NOTICE"
        // contém a substring "ICE", então toda linha em nível NOTICE cai em DTLS
        // antes de chegar na checagem de PJSIP. Falso positivo pré-existente,
        // caracterizado aqui (não corrigido nesta fase).
        mockHelperLines(
                List.of(
                        "[2026-07-13 10:00:00] NOTICE[123]: res_pjsip: Endpoint 9001 is now Reachable"));

        mockMvc.perform(get("/api/v1/logs/asterisk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entries[0].category").value("DTLS"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void asteriskSnapshot_filtraPorCategoria() throws Exception {
        mockHelperLines(
                List.of(
                        "[2026-07-13 10:00:00] NOTICE[123]: res_pjsip: Endpoint 9001 is now Reachable",
                        "[2026-07-13 10:00:01] ERROR[124]: Falha inesperada sem palavras-chave"));

        mockMvc.perform(get("/api/v1/logs/asterisk?levels=ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entries[0].category").value("ERROR"));
    }

    // ── Asterisk download ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void asteriskDownload_devolveAnexoTextoEGravaAuditoria() throws Exception {
        mockHelperLines(List.of("linha de log asterisk"));

        mockMvc.perform(get("/api/v1/logs/asterisk/download"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.startsWith(
                                                "attachment; filename=asterisk-")));

        verify(auditService).log(any(), eq("LOGS_DOWNLOAD"), eq("Asterisk log"), eq(true));
    }

    // ── Asterisk status (AMI) ─────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void asteriskStatus_hostAmiInalcancavel_devolveOkFalseComErro() throws Exception {
        // amiHost default é "asterisk" — não resolve fora da rede docker do stack,
        // então a conexão TCP falha e o controller captura a exceção (comportamento
        // atual: nunca propaga erro 500, sempre 200 com ok=false).
        mockMvc.perform(get("/api/v1/logs/asterisk/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").exists());
    }
}
