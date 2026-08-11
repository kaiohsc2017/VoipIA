package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre a regra de negócio do agregado diário de agente de voz (sub-fase 9b): recorte
 * (clip) de períodos de estado que cruzam a meia-noite ou ainda estão abertos, cálculo de
 * ocupação, e os limites do reprocessamento sob demanda. Não testa o controller (mesma
 * convenção já estabelecida no domínio callcenter nesta sessão).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterAgentAggregationServiceTest {

    @Mock
    private CcAgentRepository agentRepository;
    @Mock
    private CcAgentStateRepository agentStateRepository;
    @Mock
    private CcInteractionRepository interactionRepository;
    @Mock
    private CcAggAgentDailyRepository aggRepository;

    private CallCenterAgentAggregationService service;

    private CcAgent agent;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDateTime DAY_START = DATE.atStartOfDay();
    private static final LocalDateTime DAY_END = DATE.plusDays(1).atStartOfDay();

    @BeforeEach
    void setUp() {
        service = new CallCenterAgentAggregationService(agentRepository, agentStateRepository, interactionRepository, aggRepository);
        agent = CcAgent.builder().id(1L).name("João").build();
    }

    /** Stubs comuns a aggregateDate — só chamados pelos testes que exercitam o cálculo de
     * verdade (não pelos testes de reprocessRange, que mockam aggregateDate direto). */
    private void stubAggregateDateDefaults() {
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(agent));
        when(interactionRepository.findByAgentIdAndQueuedAtBetween(eq(1L), any(), any())).thenReturn(List.of());
        when(aggRepository.findByAgentIdAndDate(1L, DATE)).thenReturn(Optional.empty());
    }

    private CcAgentState state(AgentState s, LocalDateTime start, LocalDateTime end) {
        return CcAgentState.builder().agent(agent).state(s).startedAt(start).endedAt(end).build();
    }

    @Test
    @DisplayName("período que cruza a meia-noite só conta a fatia dentro do dia agregado")
    void aggregateDate_periodCrossingMidnight_onlyCountsSliceInsideDay() {
        // Começou às 23:00 do dia ANTERIOR, terminou às 01:00 do dia agregado — só 1h (3600s)
        // do período cai dentro de [DAY_START, DAY_END).
        stubAggregateDateDefaults();
        CcAgentState crossing = state(AgentState.DISPONIVEL, DAY_START.minusHours(1), DAY_START.plusHours(1));
        when(agentStateRepository.findOverlapping(1L, DAY_START, DAY_END)).thenReturn(List.of(crossing));

        service.aggregateDate(DATE);

        var captor = org.mockito.ArgumentCaptor.forClass(CcAggAgentDaily.class);
        verify(aggRepository).save(captor.capture());
        assertThat(captor.getValue().getAvailableSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("período ainda aberto (endedAt null) conta até o fim do dia agregado, nunca além")
    void aggregateDate_openPeriod_countsUntilNowOrDayEnd() {
        // Aberto desde 22:00 do dia agregado, ainda sem endedAt — "vale até agora", mas o
        // teste roda depois dessa data (no passado), então o clip pro fim do dia prevalece:
        // 22:00 -> 00:00 do dia seguinte = 2h (7200s).
        stubAggregateDateDefaults();
        CcAgentState open = state(AgentState.EM_ATENDIMENTO, DATE.atTime(22, 0), null);
        when(agentStateRepository.findOverlapping(1L, DAY_START, DAY_END)).thenReturn(List.of(open));

        service.aggregateDate(DATE);

        var captor = org.mockito.ArgumentCaptor.forClass(CcAggAgentDaily.class);
        verify(aggRepository).save(captor.capture());
        assertThat(captor.getValue().getOccupiedSeconds()).isEqualTo(7200);
    }

    @Test
    @DisplayName("occupancyPct é null quando o agente não teve nenhum tempo logado no dia")
    void aggregateDate_noLoggedTime_occupancyPctIsNull() {
        // Só período de OFFLINE no dia — sem DISPONIVEL/EM_ATENDIMENTO/ACW, occupancyPct é null.
        stubAggregateDateDefaults();
        CcAgentState offline = state(AgentState.OFFLINE, DAY_START, DAY_END);
        when(agentStateRepository.findOverlapping(1L, DAY_START, DAY_END)).thenReturn(List.of(offline));

        service.aggregateDate(DATE);

        var captor = org.mockito.ArgumentCaptor.forClass(CcAggAgentDaily.class);
        verify(aggRepository).save(captor.capture());
        CcAggAgentDaily saved = captor.getValue();
        assertThat(saved.getOccupancyPct()).isNull();
        assertThat(saved.getOfflineSeconds()).isEqualTo(86400);
    }

    @Test
    @DisplayName("agente sem nenhuma interação no dia gera registro zerado, não é pulado")
    void aggregateDate_agentWithNoInteractions_generatesZeroedRecord() {
        stubAggregateDateDefaults();
        when(agentStateRepository.findOverlapping(1L, DAY_START, DAY_END)).thenReturn(List.of());

        service.aggregateDate(DATE);

        var captor = org.mockito.ArgumentCaptor.forClass(CcAggAgentDaily.class);
        verify(aggRepository).save(captor.capture());
        CcAggAgentDaily saved = captor.getValue();
        assertThat(saved.getAnswered()).isEqualTo(0);
        assertThat(saved.getOccupiedSeconds()).isEqualTo(0);
        assertThat(saved.getAvailableSeconds()).isEqualTo(0);
    }

    @Test
    @DisplayName("interações atendidas do agente no dia calculam avgTalkSeconds")
    void aggregateDate_answeredInteractions_computesAvgTalkSeconds() {
        LocalDateTime queuedAt = DATE.atTime(10, 0);
        CcInteraction i = CcInteraction.builder()
                .queuedAt(queuedAt).answeredAt(queuedAt.plusSeconds(5)).endedAt(queuedAt.plusSeconds(65))
                .build();
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(agent));
        when(aggRepository.findByAgentIdAndDate(1L, DATE)).thenReturn(Optional.empty());
        when(interactionRepository.findByAgentIdAndQueuedAtBetween(eq(1L), any(), any())).thenReturn(List.of(i));
        when(agentStateRepository.findOverlapping(1L, DAY_START, DAY_END)).thenReturn(List.of());

        service.aggregateDate(DATE);

        var captor = org.mockito.ArgumentCaptor.forClass(CcAggAgentDaily.class);
        verify(aggRepository).save(captor.capture());
        CcAggAgentDaily saved = captor.getValue();
        assertThat(saved.getAnswered()).isEqualTo(1);
        assertThat(saved.getAvgTalkSeconds()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("reprocessRange chama aggregateDate uma vez por dia do intervalo")
    void reprocessRange_callsAggregateDateOncePerDay() {
        CallCenterAgentAggregationService spyService = spy(
                new CallCenterAgentAggregationService(agentRepository, agentStateRepository, interactionRepository, aggRepository));
        org.mockito.Mockito.doNothing().when(spyService).aggregateDate(any());

        spyService.reprocessRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3));

        verify(spyService, times(3)).aggregateDate(any());
    }

    @Test
    @DisplayName("reprocessRange rejeita intervalo maior que 400 dias")
    void reprocessRange_rejectsIntervalLargerThan400Days() {
        assertThatThrownBy(() -> service.reprocessRange(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 dias");
    }
}
