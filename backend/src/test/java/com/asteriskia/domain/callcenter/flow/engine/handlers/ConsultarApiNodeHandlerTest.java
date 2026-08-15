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
import com.asteriskia.domain.settings.EnvFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ConsultarApiNodeHandlerTest — cobre os ramos que decidem ANTES/SEM chamar a API real (o cerne
 * do guard anti-SSRF: chave fora da allowlist, sem valor configurado, host privado/loopback),
 * mesmo padrão de {@code ConsultarBaseNodeHandlerTest}/{@code CallCenterKbAnswerServiceTest} — sem
 * mockar a cadeia fluente do {@link WebClient} para o caminho de sucesso (verboso demais para o
 * retorno; a chamada de fato é coberta por validação manual em produção).
 */
class ConsultarApiNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private EnvFileStore envFileStore;
    private ConsultarApiNodeHandler handler;
    private ChannelDriver driver;
    private FlowExecutionContext context;

    @BeforeEach
    void setUp() {
        envFileStore = mock(EnvFileStore.class);
        handler = new ConsultarApiNodeHandler(envFileStore, WebClient.builder());
        driver = mock(ChannelDriver.class);
        context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-1", driver);
    }

    private FlowGraph.Node node(String settingsKey) throws Exception {
        var props = new LinkedHashMap<String, Object>();
        if (settingsKey != null) props.put("settingsKey", settingsKey);
        var json =
                "{\"id\":\"n1\",\"type\":\"generic\",\"data\":{\"nodeType\":\"consultar_api\",\"properties\":"
                        + mapper.writeValueAsString(props) + "}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    @Test
    @DisplayName("sem settingsKey configurado: encerra sem chamar o EnvFileStore")
    void semSettingsKey_endsSafely() throws Exception {
        var n = node(null);
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
        verify(envFileStore, never()).readRaw();
    }

    @Test
    @DisplayName("settingsKey fora da allowlist: nunca resolve chave arbitrária do .env")
    void settingsKeyForaDaAllowlist_neverResolvesArbitraryKey() throws Exception {
        var n = node("BACKEND_JWT_SECRET");
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
        verify(envFileStore, never()).readRaw();
    }

    @Test
    @DisplayName("settingsKey na allowlist mas sem valor no .env: encerra")
    void settingsKeySemValor_endsSafely() throws Exception {
        when(envFileStore.readRaw()).thenReturn(Map.of());
        var n = node("CALLCENTER_API_CRM_URL");
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
    }

    @Test
    @DisplayName("URL configurada aponta para host privado/loopback: bloqueada, encerra")
    void urlPrivada_blocked() throws Exception {
        when(envFileStore.readRaw()).thenReturn(Map.of("CALLCENTER_API_CRM_URL", "http://127.0.0.1:8080/status"));
        var n = node("CALLCENTER_API_CRM_URL");
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
    }

    @Test
    @DisplayName("falha ao ler o .env: encerra sem lançar")
    void ioExceptionAoLerEnv_endsSafely() throws Exception {
        when(envFileStore.readRaw()).thenThrow(new IOException("disco cheio"));
        var n = node("CALLCENTER_API_CRM_URL");
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
    }

    @Test
    @DisplayName("sem sucesso mas com segunda aresta configurada: segue o ramo de erro sem encerrar")
    void semSucesso_comSegundaAresta_followsErrorEdge() throws Exception {
        when(envFileStore.readRaw()).thenReturn(Map.of());
        var n = node("CALLCENTER_API_CRM_URL");
        var graph =
                new FlowGraph(
                        2,
                        List.of(n),
                        List.of(new FlowGraph.Edge("e1", "n1", "sucesso"), new FlowGraph.Edge("e2", "n1", "erro")));

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isPresent();
        assertThat(edge.get().target()).isEqualTo("erro");
        verify(driver, never()).end();
    }

    @Test
    @DisplayName("URL malformada configurada: bloqueada, encerra")
    void urlMalformada_blocked() throws Exception {
        when(envFileStore.readRaw()).thenReturn(Map.of("CALLCENTER_API_CRM_URL", "not-a-url"));
        var n = node("CALLCENTER_API_CRM_URL");
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
    }
}
