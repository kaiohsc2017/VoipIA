package com.asteriskia.domain.callcenter.flow.simulation;

import java.util.List;
import java.util.Map;

/**
 * FlowSimulationResult — resultado completo de uma simulação (Fase 5d). {@code outcome} espelha
 * os mesmos rótulos do motor real ({@code FlowExecutionEngine.terminalOutcome}) para o operador
 * reconhecer o comportamento — mas nunca é persistido em {@code cc_flow_executions}.
 */
public record FlowSimulationResult(
        Long flowId,
        Long flowVersionId,
        String versionStatus,
        String outcome,
        List<FlowSimulationStepView> steps,
        Map<String, String> finalVariables) {}
