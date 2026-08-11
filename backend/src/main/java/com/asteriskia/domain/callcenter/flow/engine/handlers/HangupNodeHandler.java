package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** HangupNodeHandler — nó "encerrar" (Fase 5b): finaliza a execução. */
@Component
public class HangupNodeHandler implements NodeHandler {

    @Override
    public String nodeType() {
        return "encerrar";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        context.driver().end();
        return Optional.empty();
    }
}
