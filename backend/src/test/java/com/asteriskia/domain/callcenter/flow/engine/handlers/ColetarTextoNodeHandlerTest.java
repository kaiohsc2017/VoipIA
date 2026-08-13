package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ColetarTextoNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ColetarTextoNodeHandler handler;
    private ChannelDriver driver;
    private FlowExecutionContext context;

    @BeforeEach
    void setUp() {
        handler = new ColetarTextoNodeHandler();
        driver = mock(ChannelDriver.class);
        context = new FlowExecutionContext(1L, 1L, 1L, "chat-1", driver);
    }

    private FlowGraph.Node nodeWith(String variavel, String timeoutSegundos) throws Exception {
        var props = new java.util.LinkedHashMap<String, Object>();
        if (variavel != null) props.put("variavel", variavel);
        if (timeoutSegundos != null) props.put("timeoutSegundos", timeoutSegundos);
        var json = "{\"id\":\"n1\",\"type\":\"generic\",\"data\":{\"nodeType\":\"coletar_texto\",\"properties\":"
                + mapper.writeValueAsString(props) + "}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    @Test
    @DisplayName("texto coletado é gravado na variável do contexto e do driver, seguindo a única aresta")
    void collected_setsVariableAndFollowsEdge() throws Exception {
        when(driver.collectText(any())).thenReturn(ChannelDriver.TextResult.collected("meu-email@x.com"));
        var node = nodeWith("email", "30");
        var graph = new FlowGraph(2, List.of(node), List.of(new FlowGraph.Edge("e1", "n1", "n2")));

        var edge = handler.handle(graph, node, context);

        assertThat(edge).isPresent();
        assertThat(context.getVariable("email")).isEqualTo("meu-email@x.com");
        verify(driver).setVariable("email", "meu-email@x.com");
        verify(driver, never()).end();
    }

    @Test
    @DisplayName("hung up (sessão encerrada durante a espera) não segue nenhuma aresta e não chama end() de novo")
    void hungUp_returnsEmptyWithoutCallingEnd() throws Exception {
        when(driver.collectText(any())).thenReturn(ChannelDriver.TextResult.hungUp());
        var node = nodeWith("email", null);
        var graph = new FlowGraph(2, List.of(node), List.of());

        var edge = handler.handle(graph, node, context);

        assertThat(edge).isEmpty();
        verify(driver, never()).end();
    }

    @Test
    @DisplayName("texto coletado sem aresta de saída encerra a sessão em vez de deixá-la presa em status=bot para sempre")
    void collected_withoutOutgoingEdge_callsEnd() throws Exception {
        when(driver.collectText(any())).thenReturn(ChannelDriver.TextResult.collected("meu-email@x.com"));
        var node = nodeWith("email", null);
        var graph = new FlowGraph(2, List.of(node), List.of());

        var edge = handler.handle(graph, node, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
    }

    @Test
    @DisplayName("timeout sem aresta de saída encerra a sessão em vez de deixá-la presa esperando")
    void timeout_withoutOutgoingEdge_callsEnd() throws Exception {
        when(driver.collectText(Duration.ofSeconds(60))).thenReturn(ChannelDriver.TextResult.timeout());
        var node = nodeWith("email", null);
        var graph = new FlowGraph(2, List.of(node), List.of());

        var edge = handler.handle(graph, node, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
    }
}
