package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.audio.CallCenterAudioService;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver.PromptResult;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MenuNodeHandlerTest — roteamento por {@code sourceHandle} (schemaVersion 2, Fase 5c) e
 * compatibilidade com o formato legado {@code "1=edgeId;2=edgeId2"} (schemaVersion 1).
 */
class MenuNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CallCenterAudioService audioService = mock(CallCenterAudioService.class);
    private final MenuNodeHandler handler = new MenuNodeHandler(mapper, audioService);

    private FlowGraph.Node menuNodeV2(String opcoesMenuJson, String timeoutSegundos, String tentativas) throws Exception {
        var props = new java.util.LinkedHashMap<String, Object>();
        props.put("opcoesMenu", opcoesMenuJson);
        if (timeoutSegundos != null) props.put("timeoutSegundos", timeoutSegundos);
        if (tentativas != null) props.put("tentativas", tentativas);
        var json = "{\"id\":\"n2\",\"type\":\"generic\",\"data\":{\"nodeType\":\"menu_opcoes\",\"properties\":"
                + mapper.writeValueAsString(props) + "}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    private FlowGraph.Node menuNodeV1(String opcoes) throws Exception {
        var json = "{\"id\":\"n2\",\"type\":\"generic\",\"data\":{\"nodeType\":\"menu_opcoes\",\"properties\":{\"opcoes\":\"" + opcoes + "\"}}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    private FlowGraph graphWith(FlowGraph.Node node, List<FlowGraph.Edge> edges) {
        return new FlowGraph(2, List.of(node), edges);
    }

    // --- schemaVersion 2 (sourceHandle) ---

    @Test
    @DisplayName("v2: dígito válido segue o handle opt-<digito>")
    void v2_validDigit_followsHandle() throws Exception {
        var node = menuNodeV2("[{\"digito\":\"1\",\"rotulo\":\"Vendas\"},{\"digito\":\"2\",\"rotulo\":\"Suporte\"}]", null, null);
        var edges = List.of(
                new FlowGraph.Edge("e1", "n2", "n4", "opt-1"),
                new FlowGraph.Edge("e2", "n2", "n5", "opt-2"));
        var driver = mock(ChannelDriver.class);
        when(driver.promptChoice(List.of("1", "2"), Duration.ofSeconds(10))).thenReturn(PromptResult.chosen("2"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(graphWith(node, edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e2");
    }

    @Test
    @DisplayName("v2: timeout segue opt-timeout e NÃO desliga a chamada quando a aresta existe")
    void v2_timeout_followsTimeoutHandle_doesNotHangUp() throws Exception {
        var node = menuNodeV2("[{\"digito\":\"1\",\"rotulo\":\"Vendas\"}]", "5", null);
        var edges = List.of(
                new FlowGraph.Edge("e1", "n2", "n4", "opt-1"),
                new FlowGraph.Edge("eTimeout", "n2", "n9", "opt-timeout"));
        var driver = mock(ChannelDriver.class);
        when(driver.promptChoice(List.of("1"), Duration.ofSeconds(5))).thenReturn(PromptResult.timeout());
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(graphWith(node, edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("eTimeout");
        verify(driver, times(0)).end();
    }

    @Test
    @DisplayName("v2: timeout sem aresta opt-timeout desenhada encerra a chamada (fail-safe)")
    void v2_timeout_withoutTimeoutEdge_hangsUp() throws Exception {
        var node = menuNodeV2("[{\"digito\":\"1\",\"rotulo\":\"Vendas\"}]", null, null);
        var edges = List.of(new FlowGraph.Edge("e1", "n2", "n4", "opt-1"));
        var driver = mock(ChannelDriver.class);
        when(driver.promptChoice(any(), any())).thenReturn(PromptResult.timeout());
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(graphWith(node, edges), node, context);

        assertThat(result).isEmpty();
        verify(driver).end();
    }

    @Test
    @DisplayName("v2: dígito inválido repete até esgotar tentativas e então segue opt-invalido")
    void v2_invalidDigit_retriesThenFollowsInvalidHandle() throws Exception {
        var node = menuNodeV2("[{\"digito\":\"1\",\"rotulo\":\"Vendas\"}]", null, "2");
        var edges = List.of(
                new FlowGraph.Edge("e1", "n2", "n4", "opt-1"),
                new FlowGraph.Edge("eInvalido", "n2", "n9", "opt-invalido"));
        var driver = mock(ChannelDriver.class);
        when(driver.promptChoice(anyList(), any())).thenReturn(PromptResult.invalid("9"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(graphWith(node, edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("eInvalido");
        verify(driver, times(2)).promptChoice(anyList(), any());
    }

    @Test
    @DisplayName("v2: hangup durante o prompt encerra a execução sem seguir aresta")
    void v2_hungUp_returnsEmpty() throws Exception {
        var node = menuNodeV2("[{\"digito\":\"1\",\"rotulo\":\"Vendas\"}]", null, null);
        var driver = mock(ChannelDriver.class);
        when(driver.promptChoice(any(), any())).thenReturn(PromptResult.hungUp());
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(graphWith(node, List.of()), node, context);

        assertThat(result).isEmpty();
    }

    // --- schemaVersion 1 (compatibilidade — "1=edgeId;2=edgeId2") ---

    @Test
    @DisplayName("v1: dígito válido segue a aresta mapeada pelo id")
    void v1_validDigit_followsMappedEdge() throws Exception {
        var node = menuNodeV1("1=e2;2=e3");
        var edges = List.of(new FlowGraph.Edge("e2", "n2", "n4"), new FlowGraph.Edge("e3", "n2", "n5"));
        var driver = mock(ChannelDriver.class);
        when(driver.promptChoice(List.of("1", "2"), Duration.ofSeconds(10))).thenReturn(PromptResult.chosen("2"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(new FlowGraph(1, List.of(node), edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e3");
    }

    @Test
    @DisplayName("v1: timeout encerra a chamada e não segue aresta")
    void v1_timeout_hangsUpAndReturnsEmpty() throws Exception {
        var node = menuNodeV1("1=e2");
        var driver = mock(ChannelDriver.class);
        when(driver.promptChoice(List.of("1"), Duration.ofSeconds(10))).thenReturn(PromptResult.timeout());
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(new FlowGraph(1, List.of(node), List.of()), node, context);

        assertThat(result).isEmpty();
        verify(driver).end();
    }

    @Test
    @DisplayName("v1: sem propriedade opcoes segue a única saída sem perguntar nada")
    void v1_noOpcoes_followsOnlyEdge() throws Exception {
        var json = "{\"id\":\"n2\",\"type\":\"generic\",\"data\":{\"nodeType\":\"menu_opcoes\",\"properties\":{}}}";
        var node = mapper.readValue(json, FlowGraph.Node.class);
        var edges = List.of(new FlowGraph.Edge("e1", "n2", "n3"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", mock(ChannelDriver.class));

        var result = handler.handle(new FlowGraph(1, List.of(node), edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e1");
    }
}
