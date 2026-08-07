package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ConditionNodeHandlerTest — comparação segura de variável do contexto (nunca eval de expressão
 * arbitrária); primeira aresta de saída = ramo verdadeiro, segunda = ramo falso.
 */
class ConditionNodeHandlerTest {

    private final ConditionNodeHandler handler = new ConditionNodeHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    private FlowGraph.Node conditionNode(String expressao) throws Exception {
        var json = "{\"id\":\"n5\",\"type\":\"condicao\",\"data\":{\"nodeType\":\"condicao\",\"properties\":{\"expressao\":\"" + expressao + "\"}}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    private FlowExecutionContext contextWith(String variable, String value) {
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", mock(ChannelDriver.class));
        context.setVariable(variable, value);
        return context;
    }

    @Test
    @DisplayName("igualdade verdadeira segue a primeira aresta")
    void equalsTrue_followsFirstEdge() throws Exception {
        var node = conditionNode("status==aberto");
        var edges = List.of(new FlowGraph.Edge("e1", "n5", "n6"), new FlowGraph.Edge("e2", "n5", "n7"));
        var graph = new FlowGraph(1, List.of(node), edges);

        var result = handler.handle(graph, node, contextWith("status", "aberto"));

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e1");
    }

    @Test
    @DisplayName("igualdade falsa segue a segunda aresta")
    void equalsFalse_followsSecondEdge() throws Exception {
        var node = conditionNode("status==aberto");
        var edges = List.of(new FlowGraph.Edge("e1", "n5", "n6"), new FlowGraph.Edge("e2", "n5", "n7"));
        var graph = new FlowGraph(1, List.of(node), edges);

        var result = handler.handle(graph, node, contextWith("status", "fechado"));

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e2");
    }

    @Test
    @DisplayName("desigualdade (!=) inverte o resultado")
    void notEquals_invertsResult() throws Exception {
        var node = conditionNode("status!=aberto");
        var edges = List.of(new FlowGraph.Edge("e1", "n5", "n6"), new FlowGraph.Edge("e2", "n5", "n7"));
        var graph = new FlowGraph(1, List.of(node), edges);

        var result = handler.handle(graph, node, contextWith("status", "fechado"));

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e1");
    }

    @Test
    @DisplayName("expressão malformada é tratada como falsa, nunca executa código")
    void malformedExpression_treatedAsFalse() throws Exception {
        var node = conditionNode("isto nao eh uma expressao valida");
        var edges = List.of(new FlowGraph.Edge("e1", "n5", "n6"), new FlowGraph.Edge("e2", "n5", "n7"));
        var graph = new FlowGraph(1, List.of(node), edges);

        var result = handler.handle(graph, node, contextWith("status", "aberto"));

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e2");
    }
}
