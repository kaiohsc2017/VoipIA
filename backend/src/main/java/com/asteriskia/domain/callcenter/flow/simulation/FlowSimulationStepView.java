package com.asteriskia.domain.callcenter.flow.simulation;

/**
 * FlowSimulationStepView — um passo do roteiro de uma simulação (Fase 5d). {@code detail} traz os
 * eventos do driver simulado ocorridos durante a execução deste nó (mensagem tocada, opção
 * escolhida etc.), nunca persistido.
 */
public record FlowSimulationStepView(String nodeId, String nodeType, String label, String detail, String takenEdgeId) {}
