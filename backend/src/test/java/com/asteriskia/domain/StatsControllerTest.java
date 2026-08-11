package com.asteriskia.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.audit.AuditService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * StatsControllerTest — teste de caracterização (fase 8 da refatoração). Cobre os endpoints de
 * agregação de KPIs (conectividade, chamadas, timeseries, ranking, alertas) e o status do tronco
 * SIP via AMI. Sem cobertura anterior nenhuma — primeira rede de segurança antes de extrair o
 * cliente AMI e os montadores de ranking.
 */
@WebMvcTest(StatsController.class)
@AutoConfigureMockMvc(addFilters = false)
class StatsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private StatsCallRepository callRepo;

    @MockBean private StatsTestResultRepository testResultRepo;

    @MockBean private StatsAlertCallRepository alertCallRepo;

    @MockBean private StatsNumberTestRepository numberTestRepo;

    @MockBean private JwtService jwtService;

    @MockBean private AuditService auditService;

    @MockBean private StatsTrunkAmiClient trunkAmiClient;

    // ── Conectividade ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void connectivityStats_calculaTaxasApartirDosContadores() throws Exception {
        when(testResultRepo.countByPeriod(any(), any())).thenReturn(10L);
        when(testResultRepo.countByStatusAndPeriod(eq("SUCESSO"), any(), any())).thenReturn(8L);
        when(testResultRepo.countByStatusAndPeriod(eq("FALHA"), any(), any())).thenReturn(2L);
        when(numberTestRepo.countByIsActiveTrue()).thenReturn(20L);

        mockMvc.perform(get("/api/v1/stats/connectivity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTestsToday").value(10))
                .andExpect(jsonPath("$.successesToday").value(8))
                .andExpect(jsonPath("$.failuresToday").value(2))
                .andExpect(jsonPath("$.successRatePct").value(80.0))
                .andExpect(jsonPath("$.failRatePct").value(20.0))
                .andExpect(jsonPath("$.scheduledCount").value(20));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void connectivityStats_semTestes_taxasZeradasSemDivisaoPorZero() throws Exception {
        when(testResultRepo.countByPeriod(any(), any())).thenReturn(0L);
        when(testResultRepo.countByStatusAndPeriod(anyString(), any(), any())).thenReturn(0L);
        when(numberTestRepo.countByIsActiveTrue()).thenReturn(0L);

        mockMvc.perform(get("/api/v1/stats/connectivity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successRatePct").value(0))
                .andExpect(jsonPath("$.completionRatePct").value(0));
    }

    // ── Chamadas ──────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void callStats_calculaTaxaDeAberturaDeJiraEDuracaoMedia() throws Exception {
        when(callRepo.countByPeriod(any(), any())).thenReturn(50L);
        when(callRepo.countWithJiraByPeriod(any(), any())).thenReturn(25L);
        when(callRepo.countWithTranscriptionByPeriod(any(), any())).thenReturn(40L);
        when(callRepo.avgDurationByPeriod(any(), any())).thenReturn(123.4);

        mockMvc.perform(get("/api/v1/stats/calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(50))
                .andExpect(jsonPath("$.jiraSuccessRatePct").value(50.0))
                .andExpect(jsonPath("$.avgDurationSecs").value(123));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void callsTimeseries_mapeiaLinhasParaPontosDoGrafico() throws Exception {
        when(callRepo.countByDay(any(), any()))
                .thenReturn(
                        List.<Object[]>of(
                                new Object[] {java.sql.Date.valueOf("2026-07-10"), 5L, 2L, 90.0}));

        mockMvc.perform(get("/api/v1/stats/calls/timeseries?period=week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].total").value(5))
                .andExpect(jsonPath("$[0].jiraOpened").value(2))
                .andExpect(jsonPath("$[0].avgDuration").value(90));
    }

    // ── Ranking de atendimentos ───────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void callsRanking_montaTopClientsByTypeETrendComPeriodoAnterior() throws Exception {
        when(callRepo.topClients(any(), any(), anyInt(), anyBoolean(), anySet(), isNull()))
                .thenReturn(List.<Object[]>of(new Object[] {"Cliente A", 3L}));
        when(callRepo.byCallType(any(), any(), anyBoolean(), anySet(), isNull()))
                .thenReturn(List.<Object[]>of(new Object[] {"Incidente", 5L}));
        when(callRepo.topResolutions(any(), any(), anyInt(), anyBoolean(), anySet(), isNull()))
                .thenReturn(List.<Object[]>of(new Object[] {"Resolvido", 4L}));
        when(callRepo.topSubjectsByCallType(
                        any(), any(), anyString(), anyInt(), anyBoolean(), anySet(), isNull()))
                .thenReturn(List.<Object[]>of(new Object[] {"Rede", 2L}));
        when(callRepo.avgDurationByCallType(any(), any(), anyBoolean(), anySet(), isNull()))
                .thenReturn(List.<Object[]>of(new Object[] {"Incidente", 60.0}));

        mockMvc.perform(get("/api/v1/stats/calls/ranking?period=week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topClients[0].label").value("Cliente A"))
                .andExpect(jsonPath("$.topClients[0].total").value(3))
                .andExpect(jsonPath("$.byType[0].label").value("Incidente"))
                .andExpect(jsonPath("$.topSubjectsByType.Incidente[0].label").value("Rede"))
                .andExpect(jsonPath("$.avgDurationByType[0].avgDurationSecs").value(60.0))
                .andExpect(jsonPath("$.trend.topClientsTotal").value(3))
                .andExpect(jsonPath("$.trend.topClientsPrevTotal").value(3));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void callsRanking_limiteForaDaFaixa_ehLimitadoA50() throws Exception {
        when(callRepo.topClients(any(), any(), eq(50), anyBoolean(), anySet(), isNull()))
                .thenReturn(List.of());
        when(callRepo.byCallType(any(), any(), anyBoolean(), anySet(), isNull()))
                .thenReturn(List.of());
        when(callRepo.topResolutions(any(), any(), eq(50), anyBoolean(), anySet(), isNull()))
                .thenReturn(List.of());
        when(callRepo.avgDurationByCallType(any(), any(), anyBoolean(), anySet(), isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/stats/calls/ranking?limit=999")).andExpect(status().isOk());
    }

    // ── Alertas ───────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void alertStats_calculaTaxaDeAtendimentoEDeEnvioTelegram() throws Exception {
        when(alertCallRepo.countByPeriod(any(), any())).thenReturn(10L);
        when(alertCallRepo.countByStatusAndPeriod(eq("ATENDIDA"), any(), any())).thenReturn(7L);
        when(alertCallRepo.countByStatusAndPeriod(eq("NAO_ATENDIDA"), any(), any())).thenReturn(2L);
        when(alertCallRepo.countByStatusAndPeriod(eq("FALHA"), any(), any())).thenReturn(1L);
        when(alertCallRepo.countTelegramSentByPeriod(any(), any())).thenReturn(9L);

        mockMvc.perform(get("/api/v1/stats/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answeredRatePct").value(70.0))
                .andExpect(jsonPath("$.telegramSuccessRatePct").value(90.0));
    }

    // ── Tronco SIP ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void trunkStatus_delegaParaStatsTrunkAmiClientEDevolveOResultado() throws Exception {
        when(trunkAmiClient.queryTrunkStatus()).thenReturn(Map.of("status", "ONLINE", "rttMs", 12));

        mockMvc.perform(get("/api/v1/stats/trunk-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.rttMs").value(12));
    }
}
