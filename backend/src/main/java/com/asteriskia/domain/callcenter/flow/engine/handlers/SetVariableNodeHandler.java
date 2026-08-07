package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** SetVariableNodeHandler — nó "definir_variavel" (Fase 5b): grava {@code valor} em {@code variavel}. */
@Component
public class SetVariableNodeHandler implements NodeHandler {

    @Override
    public String nodeType() {
        return "definir_variavel";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var variavel = node.data().property("variavel");
        var valor = node.data().property("valor");
        if (variavel != null && !variavel.isBlank()) {
            context.setVariable(variavel, valor);
            context.driver().setVariable(variavel, valor);
        }
        return graph.outgoingEdges(node.id()).stream().findFirst();
    }
}
