package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** StartNodeHandler — nó "início" (Fase 5b): só segue para a única saída. */
@Component
public class StartNodeHandler implements NodeHandler {

    @Override
    public String nodeType() {
        return "inicio";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        return graph.outgoingEdges(node.id()).stream().findFirst();
    }
}
