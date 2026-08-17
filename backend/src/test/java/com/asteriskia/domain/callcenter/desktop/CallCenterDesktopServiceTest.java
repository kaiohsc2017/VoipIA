package com.asteriskia.domain.callcenter.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcPauseReason;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.interaction.Direction;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.asteriskia.domain.callcenter.reports.AgentAdherenceRow;
import com.asteriskia.domain.callcenter.reports.AgentGamificationRow;
import com.asteriskia.domain.callcenter.reports.AgentProductivityReport;
import com.asteriskia.domain.callcenter.reports.CallCenterAgentAdherenceService;
import com.asteriskia.domain.callcenter.reports.CallCenterGamificationService;
import com.asteriskia.domain.callcenter.reports.CallCenterProductivityService;
import com.asteriskia.domain.callcenter.reports.CcAggAgentDaily;
import com.asteriskia.domain.callcenter.reports.CcAggAgentDailyRepository;
import com.asteriskia.domain.callcenter.reports.GamificationReport;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallTranscriptSegmentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CallCenterDesktopServiceTest {

    @Mock
    private CallCenterAgentStateService agentStateService;
    @Mock
    private CcInteractionRepository interactionRepository;
    @Mock
    private CcAgentStateRepository agentStateRepository;
    @Mock
    private CcRecordingRepository recordingRepository;
    @Mock
    private CallAudioFileRepository audioFileRepository;
    @Mock
    private CallTranscriptSegmentRepository transcriptSegmentRepository;
    @Mock
    private CcAggAgentDailyRepository aggRepository;
    @Mock
    private CallCenterAgentAdherenceService adherenceService;
    @Mock
    private CallCenterProductivityService productivityService;
    @Mock
    private CallCenterGamificationService gamificationService;
    @Mock
    private com.asteriskia.domain.callcenter.quality.CallCenterQualityCoachingService qualityCoachingService;

    private CallCenterDesktopService service;
    private CcAgent agent;

    @BeforeEach
    void setUp() {
        service = new CallCenterDesktopService(
                agentStateService,
                interactionRepository,
                agentStateRepository,
                recordingRepository,
                audioFileRepository,
                transcriptSegmentRepository,
                aggRepository,
                adherenceService,
                productivityService,
                gamificationService,
                qualityCoachingService);

        agent = CcAgent.builder().id(42L).name("Agente Teste").active(true).build();
    }

    @Test
    @DisplayName("resumo() calcula estatísticas do próprio dia e adere ao escopo do agente logado")
    void resumo_calculatesDailyStatsForLoggedAgent() {
        when(agentStateService.currentAgent()).thenReturn(agent);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = LocalDate.now().atStartOfDay();

        CcQueue queue = CcQueue.builder().id(10L).name("Suporte").build();
        CcInteraction i1 = CcInteraction.builder()
                .id(100L).agent(agent).queue(queue).queuedAt(now.minusMinutes(30))
                .answeredAt(now.minusMinutes(29)).endedAt(now.minusMinutes(24)).build();

        when(interactionRepository.findByAgentIdAndQueuedAtBetween(eq(42L), any(), any()))
                .thenReturn(List.of(i1));

        CcAgentState s1 = CcAgentState.builder()
                .agent(agent).state(AgentState.EM_ATENDIMENTO).startedAt(now.minusMinutes(29)).endedAt(now.minusMinutes(24)).build();
        when(agentStateRepository.findOverlapping(eq(42L), any(), any()))
                .thenReturn(List.of(s1));

        when(adherenceService.adherence(eq(42L), any(), any()))
                .thenReturn(List.of(new AgentAdherenceRow(LocalDate.now(), 28800L, 28000L, BigDecimal.valueOf(97.22))));

        DesktopSummaryView summary = service.resumo();

        assertThat(summary.callsAnsweredToday()).isEqualTo(1);
        assertThat(summary.avgTalkSeconds()).isEqualTo(300);
        assertThat(summary.adherencePct()).isEqualTo(BigDecimal.valueOf(97.22));
    }

    @Test
    @DisplayName("historico() rejeita janela maior que 90 dias com 400 Bad Request")
    void historico_rejectsWindowGreaterThan90Days() {
        when(agentStateService.currentAgent()).thenReturn(agent);
        LocalDate de = LocalDate.now().minusDays(100);
        LocalDate ate = LocalDate.now();

        assertThatThrownBy(() -> service.historico(de, ate))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("90 dias");
    }

    @Test
    @DisplayName("tendencia() devolve série histórica preenchida para o número de dias solicitado")
    void tendencia_returnsTrendSeries() {
        when(agentStateService.currentAgent()).thenReturn(agent);
        CcAggAgentDaily agg = CcAggAgentDaily.builder()
                .agent(agent).date(LocalDate.now())
                .answered(10).avgTalkSeconds(BigDecimal.valueOf(250))
                .occupiedSeconds(2000).availableSeconds(1000).build();

        when(aggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(eq(42L), any(), any()))
                .thenReturn(List.of(agg));

        List<DesktopTrendPoint> points = service.tendencia(7);

        assertThat(points).hasSize(7);
        assertThat(points.get(6).answeredCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("ranking() anonymizes top 3 and locates logged agent position")
    void ranking_returnsAnonymousTop3AndAgentPosition() {
        when(agentStateService.currentAgent()).thenReturn(agent);
        AgentGamificationRow r1 = new AgentGamificationRow(1, 10L, "Outro 1", 50, 10, BigDecimal.valueOf(90));
        AgentGamificationRow r2 = new AgentGamificationRow(2, 42L, "Agente Teste", 40, 5, BigDecimal.valueOf(85));
        when(gamificationService.rank(any(), any(), eq(1)))
                .thenReturn(new GamificationReport(1, List.of(r1, r2), List.of()));

        DesktopRankingView ranking = service.ranking(null, null);

        assertThat(ranking.position()).isEqualTo(2);
        assertThat(ranking.tierLabel()).isEqualTo("Top Performer");
        assertThat(ranking.top3Anonymous()).hasSize(2);
        assertThat(ranking.top3Anonymous().get(0).label()).isEqualTo("Agente #1");
    }

    @Test
    @DisplayName("avaliacoes() delega para o qualityCoachingService com o agente logado")
    void avaliacoes_delegatesToQualityCoachingService() {
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(qualityCoachingService.getEvaluationsForAgent(eq(agent), any(), any()))
                .thenReturn(List.of());

        List<com.asteriskia.domain.callcenter.quality.DesktopEvaluationDetailView> list =
                service.avaliacoes(null, null);

        assertThat(list).isEmpty();
    }

    @Test
    @DisplayName("contestarAvaliacao() repassa justificativa e agente logado")
    void contestarAvaliacao_callsServiceWithAgent() {
        when(agentStateService.currentAgent()).thenReturn(agent);
        com.asteriskia.domain.callcenter.quality.AppealView appeal =
                new com.asteriskia.domain.callcenter.quality.AppealView(
                        1L, 10L, 42L, "Agente Teste", 100L, "Motivo", "PENDENTE", null, null, null, LocalDateTime.now());

        when(qualityCoachingService.createAppeal(eq(10L), eq(agent), eq("Justificativa válida")))
                .thenReturn(appeal);

        var req = new com.asteriskia.domain.callcenter.quality.CreateAppealRequest("Justificativa válida");
        var result = service.contestarAvaliacao(10L, req);

        assertThat(result.status()).isEqualTo("PENDENTE");
        assertThat(result.reason()).isEqualTo("Motivo");
    }

    @Test
    @DisplayName("coaching() busca planos do agente logado")
    void coaching_fetchesPlansForAgent() {
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(qualityCoachingService.getCoachingPlansForAgent(eq(42L)))
                .thenReturn(List.of());

        var list = service.coaching();
        assertThat(list).isEmpty();
    }
}
