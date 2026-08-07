package com.asteriskia.domain.callcenter.flow.engine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.CallCenterFlowService;
import com.asteriskia.domain.callcenter.flow.CcFlow;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        var response = controller().steps(1L, 5L);

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
