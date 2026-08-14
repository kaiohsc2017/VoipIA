package com.asteriskia.domain.callcenter.flow.simulation;

import java.util.List;
import java.util.Map;

/**
 * FlowSimulationRequest — corpo do {@code POST /api/v1/callcenter/fluxos/{id}/simulate} (Fase
 * 5d). {@code variaveis} preenche o contexto de execução antes do primeiro nó (ex.: simular uma
 * variável que normalmente viria de um nó "coletar_texto" anterior). {@code respostasSimuladas} é
 * consumida em ordem sempre que o fluxo pede entrada do "cliente" (menu, coleta de texto,
 * gravação de resposta) — sem resposta disponível, o driver simulado responde com timeout/
 * desistência, igual a um cliente real que não responde.
 */
public record FlowSimulationRequest(Map<String, String> variaveis, List<String> respostasSimuladas) {

    public static FlowSimulationRequest empty() {
        return new FlowSimulationRequest(Map.of(), List.of());
    }

    public FlowSimulationRequest {
        variaveis = variaveis == null ? Map.of() : Map.copyOf(variaveis);
        respostasSimuladas = respostasSimuladas == null ? List.of() : List.copyOf(respostasSimuladas);
    }
}
