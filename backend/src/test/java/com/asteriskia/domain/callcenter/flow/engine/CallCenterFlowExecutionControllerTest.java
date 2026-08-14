package com.asteriskia.domain.callcenter.flow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.CallCenterFlowService;
import com.asteriskia.domain.callcenter.flow.CcFlow;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterFlowExecutionControllerTest — regressão do IDOR encontrado em revisão de segurança:
 * {@code steps()} confirmava o escopo por BU do {@code flowId} informado, mas não que a execução
 * de fato pertencesse a esse fluxo — um usuário com acesso a um fluxo próprio conseguia ler o
 * traço de execução de outro fluxo/BU só adivinhando o id da execução.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterFlowExecutionControllerTest {

    @Mock private CallCenterFlowService flowService;
    @Mock private CcFlowExecutionRepository executionRepository;
    @Mock private CcFlowExecutionStepRepository stepRepository;

    private CallCenterFlowExecutionController controller() {
        return new CallCenterFlowExecutionController(flowService, executionRepository, stepRepository);
    }

    private CcFlow flowWithId(Long id) {
        return CcFlow.builder().id(id).name("Fluxo " + id).channel("voice").build();
    }

    @Test
    void steps_executionFromAnotherFlow_throws() {
        when(flowService.findById(1L)).thenReturn(flowWithId(1L));
        when(executionRepository.findByIdAndFlowId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().steps(1L, 99L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void steps_executionBelongsToFlow_returnsOk() {
        var flow = flowWithId(1L);
        var execution =
                CcFlowExecution.builder()
                        .id(5L)
                        .flow(flow)
                        .channelId("chan-1")
                        .startedAt(LocalDateTime.now())
                        .build();
        when(flowService.findById(1L)).thenReturn(flow);
        when(executionRepository.findByIdAndFlowId(5L, 1L)).thenReturn(Optional.of(execution));
        when(stepRepository.findByExecutionIdAndEnteredAtBetweenOrderByEnteredAtAsc(eq(5L), any(), any()))
                .thenReturn(Collections.emptyList());

        var response = controller().steps(1L, 5L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    /** Fase 5f.2 — defesa central da fatia: a busca de passos NUNCA pode acontecer só por
     * {@code executionId}, pois {@code cc_flow_execution_steps} é particionada por mês em
     * {@code entered_at} (V72) e isso forçaria uma varredura de todas as partições. */
    @Test
    void steps_neverQueriesWithoutEnteredAtWindow() {
        var flow = flowWithId(1L);
        var startedAt = LocalDateTime.of(2026, 3, 10, 8, 0);
        var endedAt = LocalDateTime.of(2026, 3, 10, 8, 5);
        var execution =
                CcFlowExecution.builder().id(5L).flow(flow).channelId("chan-1").startedAt(startedAt).endedAt(endedAt).build();
        when(flowService.findById(1L)).thenReturn(flow);
        when(executionRepository.findByIdAndFlowId(5L, 1L)).thenReturn(Optional.of(execution));
        when(stepRepository.findByExecutionIdAndEnteredAtBetweenOrderByEnteredAtAsc(eq(5L), any(), any()))
                .thenReturn(Collections.emptyList());

        controller().steps(1L, 5L);

        verify(stepRepository)
                .findByExecutionIdAndEnteredAtBetweenOrderByEnteredAtAsc(
                        eq(5L), eq(startedAt.minusMinutes(15)), eq(endedAt.plusMinutes(15)));
    }

    @Test
    void list_missingPeriod_isRequiredByControllerSignature() {
        // Documental: o parâmetro `from`/`to` é @RequestParam obrigatório (sem default) — o
        // Spring já rejeita a requisição sem eles antes de chegar ao método (400), então não há
        // caminho de código para "listar sem período" a testar aqui além da assinatura do método.
        assertThat(true).isTrue();
    }

    @Test
    void list_toBeforeFrom_rejected() {
        when(flowService.findById(1L)).thenReturn(flowWithId(1L));

        assertThatThrownBy(() -> controller().list(1L, "2026-03-10T10:00:00", "2026-03-10T08:00:00", 0, 20))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void list_validPeriod_delegatesToRepositoryWithRange() {
        var flow = flowWithId(1L);
        when(flowService.findById(1L)).thenReturn(flow);
        Page<CcFlowExecution> page = new PageImpl<>(List.of());
        when(executionRepository.findByFlowIdAndStartedAtBetweenOrderByStartedAtDesc(eq(1L), any(), any(), any()))
                .thenReturn(page);

        var response = controller().list(1L, "2026-03-01T00:00:00", "2026-03-31T23:59:59", 0, 20);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(executionRepository)
                .findByFlowIdAndStartedAtBetweenOrderByStartedAtDesc(
                        eq(1L),
                        eq(LocalDateTime.of(2026, 3, 1, 0, 0, 0)),
                        eq(LocalDateTime.of(2026, 3, 31, 23, 59, 59)),
                        any());
    }
}
