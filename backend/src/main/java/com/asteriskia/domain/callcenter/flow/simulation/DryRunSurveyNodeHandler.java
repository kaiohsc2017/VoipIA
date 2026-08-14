package com.asteriskia.domain.callcenter.flow.simulation;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Optional;

/**
 * DryRunSurveyNodeHandler — substituto em "modo seco" do nó "pesquisa_satisfacao" (Fase 21) usado
 * exclusivamente pelo simulador (Fase 5d). Nunca chama {@code CallCenterSurveyRunner} — o modo
 * {@code FALADA_IA} da pesquisa real transcreve/classifica a resposta via Gemini de forma
 * assíncrona, o que geraria custo de IA e um artefato de gravação fantasma se disparado durante um
 * dry-run em loop. Só registra que a pesquisa foi pulada e segue a primeira aresta de saída — mesmo
 * comportamento fail-open do handler real quando não há pesquisa ativa configurada.
 */
public class DryRunSurveyNodeHandler implements NodeHandler {

    @Override
    public String nodeType() {
        return "pesquisa_satisfacao";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        context.driver().playMessage(null, "[SIMULADO] Pesquisa de satisfação pulada durante a simulação.");
        return graph.outgoingEdges(node.id()).stream().findFirst();
    }
}
