package com.asteriskia.domain.callcenter.flow.engine;

import java.util.Optional;

/**
 * NodeHandler — interpreta um tipo de nó do grafo (Fase 5b). Um handler por
 * {@code FlowGraphNodeType.type()} marcado {@code implementado=true} no catálogo
 * ({@code FlowGraphNodeCatalog}) — o motor ({@link FlowExecutionEngine}) despacha por
 * {@code nodeType()}.
 */
public interface NodeHandler {

    /** O {@code type} do catálogo que este handler interpreta (ex.: {@code "menu_opcoes"}). */
    String nodeType();

    /**
     * Executa o nó e decide qual aresta seguir. {@code Optional.empty()} sinaliza fim da
     * execução (o próprio handler já deve ter chamado {@code driver.end()}/{@code
     * transferToQueue} antes de retornar vazio).
     */
    Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context);
}
