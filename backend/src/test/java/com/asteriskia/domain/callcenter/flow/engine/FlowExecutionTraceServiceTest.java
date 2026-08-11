package com.asteriskia.domain.callcenter.flow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** FlowExecutionTraceServiceTest — persistência do início/fim de execução e de cada passo do traço. */
@ExtendWith(MockitoExtension.class)
class FlowExecutionTraceServiceTest {

    @Mock private CcFlowExecutionRepository executionRepository;
    @Mock private CcFlowExecutionStepRepository stepRepository;

    private FlowExecutionTraceService service() {
        return new FlowExecutionTraceService(executionRepository, stepRepository);
    }

    @Test
    void startExecution_savesWithFlowAndVersion() {
        var flow = CcFlow.builder().id(1L).build();
        var version = CcFlowVersion.builder().id(10L).build();
        when(executionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var execution = service().startExecution(flow, version, "chan-1", "chan-1");

        assertThat(execution.getFlow()).isEqualTo(flow);
        assertThat(execution.getFlowVersion()).isEqualTo(version);
        assertThat(execution.getChannelId()).isEqualTo("chan-1");
    }

    @Test
    void enterStep_savesWithNodeMetadata() {
        var execution = CcFlowExecution.builder().id(1L).build();
        when(stepRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var step = service().enterStep(execution, "n1", "inicio", null);

        assertThat(step.getExecution()).isEqualTo(execution);
        assertThat(step.getNodeId()).isEqualTo("n1");
        assertThat(step.getNodeType()).isEqualTo("inicio");
    }

    @Test
    void exitStep_setsExitedAtAndTakenEdge() {
        var step = CcFlowExecutionStep.builder().id(1L).build();
        when(stepRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().exitStep(step, "e1");

        assertThat(step.getTakenEdge()).isEqualTo("e1");
        assertThat(step.getExitedAt()).isNotNull();
    }

    @Test
    void endExecution_setsOutcomeAndEndedAt() {
        var execution = CcFlowExecution.builder().id(1L).build();
        when(executionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().endExecution(execution, "COMPLETED", "n7");

        assertThat(execution.getOutcome()).isEqualTo("COMPLETED");
        assertThat(execution.getLastNodeId()).isEqualTo("n7");
        assertThat(execution.getEndedAt()).isNotNull();
    }
}
