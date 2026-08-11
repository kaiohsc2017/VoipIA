package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver.PromptResult;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MenuNodeHandlerTest — parsing do formato {@code "1=edgeId;2=edgeId2"} e roteamento pelo dígito. */
class MenuNodeHandlerTest {

    private final MenuNodeHandler handler = new MenuNodeHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    private FlowGraph.Node menuNode(String opcoes) throws Exception {
        var json = "{\"id\":\"n2\",\"type\":\"menu_opcoes\",\"data\":{\"nodeType\":\"menu_opcoes\",\"properties\":{\"opcoes\":\"" + opcoes + "\"}}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    private FlowGraph graphWith(FlowGraph.Node node, List<FlowGraph.Edge> edges) {
        return new FlowGraph(1, List.of(node), edges);
    }

    @Test
    @DisplayName("dígito válido segue a aresta mapeada")
    void validDigit_followsMappedEdge() throws Exception {
        var node = menuNode("1=e2;2=e3");
        var edges = List.of(new FlowGraph.Edge("e2", "n2", "n4"), new FlowGraph.Edge("e3", "n2", "n5"));
        var driver = mock(ChannelDriver.class);
        when(driver.promptChoice(List.of("1", "2"), Duration.ofSeconds(10))).thenReturn(PromptResult.chosen("2"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(graphWith(node, edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e3");
    }

    @Test
    @DisplayName("timeout encerra a chamada e não segue aresta")
    void timeout_hangsUpAndReturnsEmpty() throws Exception {
        var node = menuNode("1=e2");
        var driver = mock(ChannelDriver.class);
        when(driver.promptChoice(List.of("1"), Duration.ofSeconds(10))).thenReturn(PromptResult.timeout());
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(graphWith(node, List.of()), node, context);

        assertThat(result).isEmpty();
        verify(driver).end();
    }

    @Test
    @DisplayName("sem propriedade opcoes segue a única saída sem perguntar nada")
    void noOpcoes_followsOnlyEdge() throws Exception {
        var json = "{\"id\":\"n2\",\"type\":\"menu_opcoes\",\"data\":{\"nodeType\":\"menu_opcoes\",\"properties\":{}}}";
        var node = mapper.readValue(json, FlowGraph.Node.class);
        var edges = List.of(new FlowGraph.Edge("e1", "n2", "n3"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", mock(ChannelDriver.class));

        var result = handler.handle(graphWith(node, edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e1");
    }
}
