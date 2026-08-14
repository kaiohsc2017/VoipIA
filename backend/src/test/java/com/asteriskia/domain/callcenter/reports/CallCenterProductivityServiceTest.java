package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.CcPauseReason;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.insights.AgentReportAggregationService;
import com.asteriskia.domain.insights.AgentReportContent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** Cobre o resumo/timeline/reuso de análise da Fase 8 do relatório de "Produtividade" (Fase 27). */
@ExtendWith(MockitoExtension.class)
class CallCenterProductivityServiceTest {

    @Mock private CcAgentRepository agentRepository;
    @Mock private CcAggAgentDailyRepository aggRepository;
    @Mock private CcAgentStateRepository stateRepository;
    @Mock private AgentReportAggregationService agentReportAggregationService;

    private CallCenterProductivityService service;

    private final LocalDate from = LocalDate.of(2026, 8, 1);
    private final LocalDate to = LocalDate.of(2026, 8, 14);
    private CcAgent agent;

    @BeforeEach
    void setUp() {
        service = new CallCenterProductivityService(agentRepository, aggRepository, stateRepository, agentReportAggregationService);
        agent = CcAgent.builder().id(1L).name("Kaio").build();
    }

    private AgentReportContent emptyContent() {
        return new AgentReportContent(
                new AgentReportContent.Aggregate(0, null, 0, List.of(), Map.of()), List.of(), null);
    }

    @Test
    void build_agenteInexistente_lanca404() {
        when(agentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.build(99L, from, to))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Agente não encontrado");
    }

    @Test
    void build_semDadosNoPeriodo_resumoZerado() {
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));
        when(aggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(1L, from, to)).thenReturn(List.of());
        when(stateRepository.findOverlapping(anyLong(), any(), any())).thenReturn(List.of());
        when(agentReportAggregationService.buildAggregate("Kaio", "callcenter", from, to)).thenReturn(emptyContent());

        var report = service.build(1L, from, to);

        assertThat(report.resumo().totalAtendidas()).isZero();
        assertThat(report.resumo().occupancyPct()).isNull();
        assertThat(report.timeline()).isEmpty();
        assertThat(report.pontosFortes()).isEmpty();
        assertThat(report.pontosMelhoria()).isEmpty();
    }

    @Test
    void build_resumoPonderaTmaENpsPeloVolumeDiario() {
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));
        CcAggAgentDaily dia1 = CcAggAgentDaily.builder()
                .agent(agent).date(from).answered(2).avgTalkSeconds(new BigDecimal("100"))
                .avgNpsScore(new BigDecimal("10")).outboundPlaced(0)
                .occupiedSeconds(200).availableSeconds(100).pausedSeconds(0).offlineSeconds(0)
                .computedAt(LocalDateTime.now().minusDays(1)).build();
        CcAggAgentDaily dia2 = CcAggAgentDaily.builder()
                .agent(agent).date(from.plusDays(1)).answered(8).avgTalkSeconds(new BigDecimal("50"))
                .avgNpsScore(new BigDecimal("5")).outboundPlaced(0)
                .occupiedSeconds(400).availableSeconds(400).pausedSeconds(0).offlineSeconds(0)
                .computedAt(LocalDateTime.now().minusDays(1)).build();
        when(aggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(1L, from, to)).thenReturn(List.of(dia1, dia2));
        when(stateRepository.findOverlapping(anyLong(), any(), any())).thenReturn(List.of());
        when(agentReportAggregationService.buildAggregate("Kaio", "callcenter", from, to)).thenReturn(emptyContent());

        var report = service.build(1L, from, to);

        assertThat(report.resumo().totalAtendidas()).isEqualTo(10);
        // TMA ponderado: (2*100 + 8*50) / 10 = 60.00 — não a média simples (75.00)
        assertThat(report.resumo().avgTalkSeconds()).isEqualByComparingTo("60.00");
        // NPS ponderado: (2*10 + 8*5) / 10 = 6.00
        assertThat(report.resumo().npsMedio()).isEqualByComparingTo("6.00");
        assertThat(report.resumo().occupiedSeconds()).isEqualTo(600);
    }

    @Test
    void build_timelineOrdenaEstadosPorInicio() {
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));
        when(aggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(1L, from, to)).thenReturn(List.of());
        CcPauseReason almoco = CcPauseReason.builder().id(1L).code("almoco").label("Almoço").productive(false).build();
        CcAgentState segundo = CcAgentState.builder()
                .id(2L).agent(agent).state(AgentState.DISPONIVEL)
                .startedAt(LocalDateTime.of(2026, 8, 1, 12, 0)).endedAt(null).build();
        CcAgentState primeiro = CcAgentState.builder()
                .id(1L).agent(agent).state(AgentState.PAUSA).pauseReason(almoco)
                .startedAt(LocalDateTime.of(2026, 8, 1, 9, 0)).endedAt(LocalDateTime.of(2026, 8, 1, 9, 30)).build();
        when(stateRepository.findOverlapping(eqLong(1L), any(), any())).thenReturn(List.of(segundo, primeiro));
        when(agentReportAggregationService.buildAggregate("Kaio", "callcenter", from, to)).thenReturn(emptyContent());

        var report = service.build(1L, from, to);

        assertThat(report.timeline()).hasSize(2);
        assertThat(report.timeline().get(0).state()).isEqualTo("PAUSA");
        assertThat(report.timeline().get(0).pauseReasonLabel()).isEqualTo("Almoço");
        assertThat(report.timeline().get(1).state()).isEqualTo("DISPONIVEL");
        assertThat(report.timeline().get(1).endedAt()).isNull();
    }

    @Test
    void build_pontosFortesEMelhoria_extraemExtremosDaNotaPorItem() {
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));
        when(aggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(1L, from, to)).thenReturn(List.of());
        when(stateRepository.findOverlapping(anyLong(), any(), any())).thenReturn(List.of());
        AgentReportContent content = new AgentReportContent(
                new AgentReportContent.Aggregate(10, new BigDecimal("80"), 0, List.of(
                        new AgentReportContent.ItemAverage(1L, "Saudação", new BigDecimal("95")),
                        new AgentReportContent.ItemAverage(2L, "Escuta ativa", new BigDecimal("40")),
                        new AgentReportContent.ItemAverage(3L, "Tom de voz", new BigDecimal("70"))
                ), Map.of()), List.of(), null);
        when(agentReportAggregationService.buildAggregate("Kaio", "callcenter", from, to)).thenReturn(content);

        var report = service.build(1L, from, to);

        assertThat(report.pontosFortes()).containsExactly("Saudação", "Tom de voz", "Escuta ativa");
        assertThat(report.pontosMelhoria()).containsExactly("Escuta ativa", "Tom de voz", "Saudação");
    }

    private static Long eqLong(long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
