package com.asteriskia.domain.callcenter.flow.simulation;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Optional;

/**
 * DryRunConsultarBaseNodeHandler — substituto em "modo seco" do nó "consultar_base" (Fase 25,
 * IA/RAG) usado exclusivamente pelo simulador (Fase 5d). Nunca chama
 * {@code CallCenterKbAnswerService} (nunca consulta o LLM/Gemini) — sem isso, cada clique em
 * "Simular" viraria custo real de IA, principalmente em loop de teste do operador. Sempre finge
 * uma resposta encontrada e segue a primeira aresta de saída, mesmo caminho "feliz" do handler
 * real — o objetivo do simulador é validar o roteamento do grafo, não o conteúdo da resposta de
 * IA.
 */
public class DryRunConsultarBaseNodeHandler implements NodeHandler {

    @Override
    public String nodeType() {
        return "consultar_base";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        context
                .driver()
                .playMessage(
                        null,
                        "[SIMULADO] Resposta da base de conhecimento — nó não consulta IA real durante a simulação.");
        var outgoing = graph.outgoingEdges(node.id());
        if (outgoing.isEmpty()) {
            context.driver().end();
            return Optional.empty();
        }
        return Optional.of(outgoing.get(0));
    }
}
