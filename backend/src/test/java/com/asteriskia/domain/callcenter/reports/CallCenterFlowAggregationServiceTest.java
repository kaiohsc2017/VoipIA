package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecution;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionRepository;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStep;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStepRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre a regra de negócio do agregado diário de fluxo/URA (sub-fase 9c.1): classificação de
 * desfecho (COMPLETED, TRANSFERRED_QUEUE/EXTENSION, ABANDONED, ERROR), duração média só das
 * execuções encerradas,
 * e abandono por nó (o nó onde a execução morreu, não apenas por onde passou).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterFlowAggregationServiceTest {

    @Mock
    private CcFlowRepository flowRepository;
    @Mock
    private CcFlowExecutionRepository executionRepository;
    @Mock
    private CcFlowExecutionStepRepository stepRepository;
    @Mock
    private CcAggFlowDailyRepository aggRepository;
    @Mock
    private CcAggFlowNodeDailyRepository nodeAggRepository;

    private CallCenterFlowAggregationService service;

    private CcFlow flow;
    private final LocalDate date = LocalDate.of(2026, 8, 14);

    @BeforeEach
    void setUp() {
        service = new CallCenterFlowAggregationService(
                flowRepository, executionRepository, stepRepository, aggRepository, nodeAggRepository);
        flow = CcFlow.builder().id(1L).name("URA Suporte").build();
    }

    private CcFlowExecution execution(Long id, String outcome, String lastNodeId, LocalDateTime started, LocalDateTime ended) {
        return CcFlowExecution.builder()
                .id(id).flow(flow).channelId("PJSIP/1").outcome(outcome).lastNodeId(lastNodeId)
                .startedAt(started).endedAt(ended).build();
    }

    @Test
    @DisplayName("classifica desfechos e calcula duração média só das execuções encerradas")
    void aggregateDate_classifiesOutcomesAndAveragesDurationOfEndedOnly() {
        LocalDateTime t = date.atTime(10, 0);
        List<CcFlowExecution> executions = List.of(
                execution(1L, "COMPLETED", "n_end", t, t.plusSeconds(30)),
                execution(2L, "TRANSFERRED_QUEUE", "n_queue", t, t.plusSeconds(10)),
                execution(3L, "TRANSFERRED_EXTENSION", "n_ext", t, t.plusSeconds(20)),
                execution(4L, "ERROR", "n_err", t, null),
                execution(5L, "ABANDONED", "n_menu", t, t.plusSeconds(40)),
                execution(6L, null, "n_menu", t, null));

        when(flowRepository.findByActiveTrue()).thenReturn(List.of(flow));
        when(executionRepository.findByFlowIdAndStartedAtBetween(eq(1L), any(), any())).thenReturn(executions);
        when(stepRepository.findByExecutionIdInAndEnteredAtBetween(anyList(), any(), any())).thenReturn(List.of());
        when(aggRepository.findByFlowIdAndDate(1L, date)).thenReturn(Optional.empty());

        service.aggregateDate(date);

        ArgumentCaptor<CcAggFlowDaily> captor = ArgumentCaptor.forClass(CcAggFlowDaily.class);
        org.mockito.Mockito.verify(aggRepository).save(captor.capture());
        CcAggFlowDaily saved = captor.getValue();
        assertThat(saved.getExecutions()).isEqualTo(6);
        assertThat(saved.getCompleted()).isEqualTo(1);
        assertThat(saved.getTransferredQueue()).isEqualTo(1);
        assertThat(saved.getTransferredExtension()).isEqualTo(1);
        assertThat(saved.getErrored()).isEqualTo(1);
        // ABANDONED explícito + o sem outcome (ainda em aberto) contam como abandono
        assertThat(saved.getAbandoned()).isEqualTo(2);
        // média de duração só das 4 execuções com endedAt != null: (30+10+20+40)/4 = 25
        assertThat(saved.getAvgDurationSeconds()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("abandono por nó conta só o nó onde a execução morreu, não todos por onde passou")
    void aggregateDate_nodeAbandonment_onlyCountsLastNodeOfDeadExecutions() {
        LocalDateTime t = date.atTime(10, 0);
        CcFlowExecution completed = execution(1L, "COMPLETED", "n_end", t, t.plusSeconds(10));
        CcFlowExecution abandonedAtMenu = execution(2L, "ABANDONED", "n_menu", t, t.plusSeconds(5));

        when(flowRepository.findByActiveTrue()).thenReturn(List.of(flow));
        when(executionRepository.findByFlowIdAndStartedAtBetween(eq(1L), any(), any()))
                .thenReturn(List.of(completed, abandonedAtMenu));
        when(aggRepository.findByFlowIdAndDate(1L, date)).thenReturn(Optional.empty());

        CcFlowExecutionStep stepStart1 = step(completed, "n_start", "start");
        CcFlowExecutionStep stepMenu1 = step(completed, "n_menu", "menu_opcoes");
        CcFlowExecutionStep stepEnd1 = step(completed, "n_end", "encerrar");
        CcFlowExecutionStep stepStart2 = step(abandonedAtMenu, "n_start", "start");
        CcFlowExecutionStep stepMenu2 = step(abandonedAtMenu, "n_menu", "menu_opcoes");
        when(stepRepository.findByExecutionIdInAndEnteredAtBetween(anyList(), any(), any()))
                .thenReturn(List.of(stepStart1, stepMenu1, stepEnd1, stepStart2, stepMenu2));
        when(nodeAggRepository.findByFlowIdAndNodeIdAndDate(eq(1L), any(), eq(date))).thenReturn(Optional.empty());

        service.aggregateDate(date);

        ArgumentCaptor<CcAggFlowNodeDaily> captor = ArgumentCaptor.forClass(CcAggFlowNodeDaily.class);
        org.mockito.Mockito.verify(nodeAggRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        var byNode = captor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(CcAggFlowNodeDaily::getNodeId, r -> r));

        assertThat(byNode.get("n_start").getEntries()).isEqualTo(2);
        assertThat(byNode.get("n_start").getAbandonedHere()).isZero();
        assertThat(byNode.get("n_menu").getEntries()).isEqualTo(2);
        // só a execução 2 morreu no menu (last_node_id="n_menu" e outcome=ABANDONED)
        assertThat(byNode.get("n_menu").getAbandonedHere()).isEqualTo(1);
        assertThat(byNode.get("n_end").getEntries()).isEqualTo(1);
        assertThat(byNode.get("n_end").getAbandonedHere()).isZero();
    }

    private CcFlowExecutionStep step(CcFlowExecution execution, String nodeId, String nodeType) {
        return CcFlowExecutionStep.builder().execution(execution).nodeId(nodeId).nodeType(nodeType)
                .enteredAt(date.atTime(10, 0)).build();
    }

    @Test
    @DisplayName("reprocessRange rejeita intervalo invertido")
    void reprocessRange_rejectsInvertedRange() {
        assertThatThrownBy(() -> service.reprocessRange(date, date.minusDays(1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("anterior");
    }

    @Test
    @DisplayName("reprocessRange rejeita intervalo maior que 400 dias")
    void reprocessRange_rejectsTooLargeRange() {
        assertThatThrownBy(() -> service.reprocessRange(date, date.plusDays(401)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 dias");
    }
}
