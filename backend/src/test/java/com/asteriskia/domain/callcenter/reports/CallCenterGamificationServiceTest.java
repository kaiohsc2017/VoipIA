package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre o ranking de gamificação (Fase 27) — em especial a regra de "volume mínimo" (D-plano:
 * agente com poucas chamadas e NPS alto não deve ranquear como o melhor da operação).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterGamificationServiceTest {

    @Mock private CcAggAgentDailyRepository aggRepository;

    private CallCenterGamificationService service;

    private final LocalDate from = LocalDate.of(2026, 8, 1);
    private final LocalDate to = LocalDate.of(2026, 8, 14);

    @BeforeEach
    void setUp() {
        service = new CallCenterGamificationService(aggRepository);
    }

    private CcAggAgentDaily row(CcAgent agent, LocalDate date, int answered, BigDecimal npsScore) {
        return CcAggAgentDaily.builder()
                .agent(agent).date(date).answered(answered).outboundPlaced(0)
                .avgNpsScore(npsScore).computedAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    @Test
    void rank_agenteAbaixoDoMinimo_naoEntraNoRankingMesmoComNpsAlto() {
        CcAgent lowVolume = CcAgent.builder().id(1L).name("Poucas Chamadas").build();
        CcAgent highVolume = CcAgent.builder().id(2L).name("Muitas Chamadas").build();

        when(aggRepository.findByDateBetweenOrderByAgentIdAscDateAsc(from, to)).thenReturn(List.of(
                row(lowVolume, from, 3, new BigDecimal("10.00")),
                row(highVolume, from, 20, new BigDecimal("8.00"))));

        GamificationReport report = service.rank(from, to, 5);

        assertThat(report.minCalls()).isEqualTo(5);
        assertThat(report.ranking()).hasSize(1);
        assertThat(report.ranking().get(0).agentId()).isEqualTo(2L);
        assertThat(report.ranking().get(0).position()).isEqualTo(1);
        assertThat(report.belowMinimum()).hasSize(1);
        assertThat(report.belowMinimum().get(0).agentId()).isEqualTo(1L);
        assertThat(report.belowMinimum().get(0).position()).isNull();
    }

    @Test
    void rank_doisElegiveis_ordenaPorNpsMedioDesc() {
        CcAgent segundo = CcAgent.builder().id(1L).name("Segundo").build();
        CcAgent primeiro = CcAgent.builder().id(2L).name("Primeiro").build();

        when(aggRepository.findByDateBetweenOrderByAgentIdAscDateAsc(from, to)).thenReturn(List.of(
                row(segundo, from, 10, new BigDecimal("7.00")),
                row(primeiro, from, 10, new BigDecimal("9.50"))));

        GamificationReport report = service.rank(from, to, 5);

        assertThat(report.ranking()).extracting(AgentGamificationRow::agentId).containsExactly(2L, 1L);
        assertThat(report.ranking()).extracting(AgentGamificationRow::position).containsExactly(1, 2);
    }

    @Test
    void rank_semMinCallsInformado_usaDefault() {
        when(aggRepository.findByDateBetweenOrderByAgentIdAscDateAsc(from, to)).thenReturn(List.of());

        GamificationReport report = service.rank(from, to, null);

        assertThat(report.minCalls()).isEqualTo(5);
        assertThat(report.ranking()).isEmpty();
        assertThat(report.belowMinimum()).isEmpty();
    }

    @Test
    void rank_ponderaNpsPeloVolumeDiario_naoMediaSimplesDosDias() {
        CcAgent agent = CcAgent.builder().id(1L).name("Agente").build();
        when(aggRepository.findByDateBetweenOrderByAgentIdAscDateAsc(from, to)).thenReturn(List.of(
                row(agent, from, 1, new BigDecimal("0.00")),
                row(agent, from.plusDays(1), 9, new BigDecimal("10.00"))));

        GamificationReport report = service.rank(from, to, 5);

        // Média ponderada: (1*0 + 9*10) / 10 = 9.00 — bem diferente da média simples (5.00).
        assertThat(report.ranking().get(0).npsMedio()).isEqualByComparingTo("9.00");
        assertThat(report.ranking().get(0).totalAtendidas()).isEqualTo(10);
    }
}
