package com.asteriskia.domain.callcenter.flow.engine;

import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowVersion;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FlowExecutionTraceService — persiste o traço de uma execução real do fluxo (Fase 5b): início/
 * fim de {@code cc_flow_executions} e cada passo em {@code cc_flow_execution_steps}. Nunca decide
 * lógica de roteamento — só registra o que {@link FlowExecutionEngine} já decidiu.
 */
@Service
@RequiredArgsConstructor
public class FlowExecutionTraceService {

    private final CcFlowExecutionRepository executionRepository;
    private final CcFlowExecutionStepRepository stepRepository;

    @Transactional
    public CcFlowExecution startExecution(
            CcFlow flow, CcFlowVersion flowVersion, String channelId, String channelUniqueId) {
        return executionRepository.save(
                CcFlowExecution.builder()
                        .flow(flow)
                        .flowVersion(flowVersion)
                        .channelId(channelId)
                        .channelUniqueId(channelUniqueId)
                        .build());
    }

    @Transactional
    public CcFlowExecutionStep enterStep(CcFlowExecution execution, String nodeId, String nodeType, String detail) {
        return stepRepository.save(
                CcFlowExecutionStep.builder()
                        .execution(execution)
                        .nodeId(nodeId)
                        .nodeType(nodeType)
                        .detail(detail)
                        .build());
    }

    @Transactional
    public void exitStep(CcFlowExecutionStep step, String takenEdgeId) {
        step.setExitedAt(LocalDateTime.now());
        step.setTakenEdge(takenEdgeId);
        stepRepository.save(step);
    }

    @Transactional
    public void endExecution(CcFlowExecution execution, String outcome, String lastNodeId) {
        execution.setEndedAt(LocalDateTime.now());
        execution.setOutcome(outcome);
        execution.setLastNodeId(lastNodeId);
        executionRepository.save(execution);
    }
}
