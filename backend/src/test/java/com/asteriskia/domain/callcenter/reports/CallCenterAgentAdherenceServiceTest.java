package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre a regra de negócio da aderência à escala (sub-fase 9c.7): sem escala cadastrada pro dia
 * da semana o resultado é {@code null} (nunca 0), e o cálculo desconta tempo OFFLINE dentro do
 * turno.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterAgentAdherenceServiceTest {

    @Mock
    private CcAgentScheduleRepository scheduleRepository;
    @Mock
    private CcAgentStateRepository agentStateRepository;

    private CallCenterAgentAdherenceService service;
    private CcAgent agent;

    @BeforeEach
    void setUp() {
        service = new CallCenterAgentAdherenceService(scheduleRepository, agentStateRepository);
        agent = CcAgent.builder().id(1L).name("Agente 1").build();
    }

    @Test
    @DisplayName("sem escala cadastrada para o dia da semana, adherencePct é null, não zero")
    void adherence_returnsNullWhenNoScheduleForDayOfWeek() {
        LocalDate friday = LocalDate.of(2026, 8, 14);
        when(scheduleRepository.findByAgentIdAndDayOfWeekAndActiveTrue(1L, 5)).thenReturn(List.of());

        List<AgentAdherenceRow> rows = service.adherence(1L, friday, friday);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).adherencePct()).isNull();
        assertThat(rows.get(0).scheduledSeconds()).isNull();
    }

    @Test
    @DisplayName("desconta tempo OFFLINE dentro do turno do cálculo de aderência")
    void adherence_excludesOfflineTimeFromWindow() {
        LocalDate friday = LocalDate.of(2026, 8, 14);
        CcAgentSchedule shift = CcAgentSchedule.builder().id(1L).agent(agent).dayOfWeek(5)
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(12, 0)).active(true).build();
        when(scheduleRepository.findByAgentIdAndDayOfWeekAndActiveTrue(1L, 5)).thenReturn(List.of(shift));

        LocalDateTime windowStart = LocalDateTime.of(friday, LocalTime.of(8, 0));
        // Disponível das 8h às 10h (2h logado), offline das 10h às 12h (2h fora) — turno de 4h.
        CcAgentState available = CcAgentState.builder().agent(agent).state(AgentState.DISPONIVEL)
                .startedAt(windowStart).endedAt(windowStart.plusHours(2)).build();
        CcAgentState offline = CcAgentState.builder().agent(agent).state(AgentState.OFFLINE)
                .startedAt(windowStart.plusHours(2)).endedAt(windowStart.plusHours(4)).build();
        when(agentStateRepository.findOverlapping(eq(1L), any(), any())).thenReturn(List.of(available, offline));

        List<AgentAdherenceRow> rows = service.adherence(1L, friday, friday);

        AgentAdherenceRow row = rows.get(0);
        assertThat(row.scheduledSeconds()).isEqualTo(4 * 3600L);
        assertThat(row.loggedSeconds()).isEqualTo(2 * 3600L);
        assertThat(row.adherencePct()).isEqualByComparingTo("50.00");
    }
}
