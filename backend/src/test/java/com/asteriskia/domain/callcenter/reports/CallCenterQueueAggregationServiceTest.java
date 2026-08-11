package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
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
 * Cobre a regra de negócio do agregado diário de fila de voz (sub-fase 9a): o que conta como
 * atendida/abandonada, o corte de nível de serviço pelo timeout da fila, e os limites do
 * reprocessamento sob demanda. Não testa o controller (mesma convenção já estabelecida no
 * domínio callcenter nesta sessão — cobertura fica no service).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterQueueAggregationServiceTest {

    @Mock
    private CcQueueRepository queueRepository;
    @Mock
    private CcInteractionRepository interactionRepository;
    @Mock
    private CcAggQueueDailyRepository aggRepository;

    private CallCenterQueueAggregationService service;

    private CcQueue queue;

    @BeforeEach
    void setUp() {
        service = new CallCenterQueueAggregationService(queueRepository, interactionRepository, aggRepository);
        queue = CcQueue.builder().id(1L).name("5001").displayName("Suporte").timeoutSeconds(20).build();
    }

    private CcInteraction answered(LocalDateTime queuedAt, LocalDateTime answeredAt, LocalDateTime endedAt) {
        return CcInteraction.builder().queue(queue).queuedAt(queuedAt).answeredAt(answeredAt).endedAt(endedAt).build();
    }

    private CcInteraction abandoned(LocalDateTime queuedAt, LocalDateTime endedAt) {
        return CcInteraction.builder().queue(queue).queuedAt(queuedAt).answeredAt(null).endedAt(endedAt).build();
    }

    @Test
    @DisplayName("interação atendida dentro do SLA da fila conta pro nível de serviço")
    void aggregateDate_answeredWithinSla_countsForServiceLevel() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        LocalDateTime queuedAt = date.atTime(10, 0, 0);
        CcInteraction i = answered(queuedAt, queuedAt.plusSeconds(10), queuedAt.plusSeconds(70)); // espera 10s <= timeout 20s

        when(queueRepository.findByActiveTrue()).thenReturn(List.of(queue));
        when(interactionRepository.findByQueueIdAndQueuedAtBetween(eq(1L), any(), any())).thenReturn(List.of(i));
        when(aggRepository.findByQueueIdAndDate(1L, date)).thenReturn(Optional.empty());

        service.aggregateDate(date);

        var captor = org.mockito.ArgumentCaptor.forClass(CcAggQueueDaily.class);
        verify(aggRepository).save(captor.capture());
        CcAggQueueDaily saved = captor.getValue();
        assertThat(saved.getReceived()).isEqualTo(1);
        assertThat(saved.getAnswered()).isEqualTo(1);
        assertThat(saved.getAbandoned()).isEqualTo(0);
        assertThat(saved.getServiceLevelPct()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("interação atendida fora do SLA da fila não conta pro nível de serviço")
    void aggregateDate_answeredOutsideSla_doesNotCountForServiceLevel() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        LocalDateTime queuedAt = date.atTime(10, 0, 0);
        CcInteraction i = answered(queuedAt, queuedAt.plusSeconds(30), queuedAt.plusSeconds(90)); // espera 30s > timeout 20s

        when(queueRepository.findByActiveTrue()).thenReturn(List.of(queue));
        when(interactionRepository.findByQueueIdAndQueuedAtBetween(eq(1L), any(), any())).thenReturn(List.of(i));
        when(aggRepository.findByQueueIdAndDate(1L, date)).thenReturn(Optional.empty());

        service.aggregateDate(date);

        var captor = org.mockito.ArgumentCaptor.forClass(CcAggQueueDaily.class);
        verify(aggRepository).save(captor.capture());
        assertThat(captor.getValue().getServiceLevelPct()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("interação abandonada entra em received/abandoned mas não em ASA/TMA")
    void aggregateDate_abandoned_excludedFromWaitAndTalkAverages() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        LocalDateTime queuedAt = date.atTime(10, 0, 0);
        CcInteraction i = abandoned(queuedAt, queuedAt.plusSeconds(45));

        when(queueRepository.findByActiveTrue()).thenReturn(List.of(queue));
        when(interactionRepository.findByQueueIdAndQueuedAtBetween(eq(1L), any(), any())).thenReturn(List.of(i));
        when(aggRepository.findByQueueIdAndDate(1L, date)).thenReturn(Optional.empty());

        service.aggregateDate(date);

        var captor = org.mockito.ArgumentCaptor.forClass(CcAggQueueDaily.class);
        verify(aggRepository).save(captor.capture());
        CcAggQueueDaily saved = captor.getValue();
        assertThat(saved.getReceived()).isEqualTo(1);
        assertThat(saved.getAbandoned()).isEqualTo(1);
        assertThat(saved.getAnswered()).isEqualTo(0);
        assertThat(saved.getAvgWaitSeconds()).isNull();
        assertThat(saved.getAvgTalkSeconds()).isNull();
        assertThat(saved.getServiceLevelPct()).isNull();
    }

    @Test
    @DisplayName("fila sem nenhuma interação no dia gera registro zerado, não é pulada")
    void aggregateDate_queueWithNoInteractions_generatesZeroedRecord() {
        LocalDate date = LocalDate.of(2026, 8, 1);

        when(queueRepository.findByActiveTrue()).thenReturn(List.of(queue));
        when(interactionRepository.findByQueueIdAndQueuedAtBetween(eq(1L), any(), any())).thenReturn(List.of());
        when(aggRepository.findByQueueIdAndDate(1L, date)).thenReturn(Optional.empty());

        service.aggregateDate(date);

        var captor = org.mockito.ArgumentCaptor.forClass(CcAggQueueDaily.class);
        verify(aggRepository).save(captor.capture());
        CcAggQueueDaily saved = captor.getValue();
        assertThat(saved.getReceived()).isEqualTo(0);
        assertThat(saved.getAnswered()).isEqualTo(0);
        assertThat(saved.getAbandoned()).isEqualTo(0);
    }

    @Test
    @DisplayName("reprocessRange chama aggregateDate uma vez por dia do intervalo")
    void reprocessRange_callsAggregateDateOncePerDay() {
        CallCenterQueueAggregationService spyService =
                spy(new CallCenterQueueAggregationService(queueRepository, interactionRepository, aggRepository));
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

    @Test
    @DisplayName("reprocessRange rejeita data final anterior à inicial")
    void reprocessRange_rejectsInvertedRange() {
        assertThatThrownBy(() -> service.reprocessRange(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
