package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.ai.AiProviderService;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.identity.CallCenterIdentityResolver;
import com.asteriskia.domain.callcenter.ia.CallCenterIaAgentConversationService;
import com.asteriskia.domain.callcenter.ia.CallCenterIaAgentConversationService.ConversationResult;
import com.asteriskia.domain.callcenter.ia.CcIaAgent;
import com.asteriskia.domain.callcenter.ia.CcIaAgentRepository;
import com.asteriskia.domain.callcenter.ia.CcIaAgentTurnRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgenteIaNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CcIaAgentRepository agentRepository;
    private CcIaAgentTurnRepository turnRepository;
    private CallCenterIaAgentConversationService conversationService;
    private CallCenterIdentityResolver identityResolver;
    private AiProviderService aiProviderService;
    private AgenteIaNodeHandler handler;
    private ChannelDriver driver;

    @BeforeEach
    void setUp() {
        agentRepository = mock(CcIaAgentRepository.class);
        turnRepository = mock(CcIaAgentTurnRepository.class);
        conversationService = mock(CallCenterIaAgentConversationService.class);
        identityResolver = mock(CallCenterIdentityResolver.class);
        aiProviderService = mock(AiProviderService.class);
        handler =
                new AgenteIaNodeHandler(
                        agentRepository, turnRepository, conversationService, identityResolver, aiProviderService);
        driver = mock(ChannelDriver.class);
        when(aiProviderService.getRawKey("gemini")).thenReturn("fake-key");
    }

    private CcIaAgent agent(int maxTurns, BigDecimal maxCostUsd, CcQueue fallback) {
        return CcIaAgent.builder()
                .id(1L)
                .name("Suporte N1")
                .systemPrompt("Você é cordial.")
                .greeting("Olá!")
                .model("gemini-2.5-flash")
                .temperature(new BigDecimal("0.20"))
                .maxTurns(maxTurns)
                .maxCostUsd(maxCostUsd)
                .fallbackQueue(fallback)
                .active(true)
                .build();
    }

    private FlowGraph.Node node(String configuracaoIaId) throws Exception {
        var props = new LinkedHashMap<String, Object>();
        if (configuracaoIaId != null) props.put("configuracaoIaId", configuracaoIaId);
        var json =
                "{\"id\":\"n1\",\"type\":\"generic\",\"data\":{\"nodeType\":\"agente_ia\",\"properties\":"
                        + mapper.writeValueAsString(props) + "}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    @Test
    @DisplayName("configuração inexistente/ausente: encerra em vez de travar a conversa")
    void semConfiguracao_endsSafely() throws Exception {
        var n = node(null);
        var context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-1", driver);
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
        verify(driver, never()).transferToQueue(any());
    }

    @Test
    @DisplayName("configuração inativa: encerra (findById filtra por active)")
    void configuracaoInativa_endsSafely() throws Exception {
        var inactive = agent(5, new BigDecimal("0.10"), null);
        inactive.setActive(false);
        when(agentRepository.findById(1L)).thenReturn(Optional.of(inactive));
        var n = node("1");
        var context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-1", driver);
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
    }

    @Test
    @DisplayName("sem API key do Gemini: escala direto para o fallback, sem tentar coletar entrada")
    void semApiKey_escalaDireto() throws Exception {
        var fallback = CcQueue.builder().id(9L).name("5099").build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent(5, new BigDecimal("0.10"), fallback)));
        when(aiProviderService.getRawKey("gemini")).thenReturn("");
        var n = node("1");
        var context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-1", driver);
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).transferToQueue("5099");
        verify(driver, never()).collectText(any());
    }

    @Test
    @DisplayName("cliente desliga/fecha o chat durante a coleta: encerra sem escalar")
    void hangUp_endsWithoutEscalating() throws Exception {
        var fallback = CcQueue.builder().id(9L).name("5099").build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent(5, new BigDecimal("0.10"), fallback)));
        when(driver.collectText(any())).thenReturn(ChannelDriver.TextResult.hungUp());
        var n = node("1");
        var context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-1", driver);
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver, never()).transferToQueue(any());
        verify(driver, never()).end();
    }

    @Test
    @DisplayName("conclusão natural sinalizada pelo modelo: toca a resposta e segue a primeira aresta")
    void completed_playsAnswerAndFollowsFirstEdge() throws Exception {
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent(5, new BigDecimal("0.10"), null)));
        when(driver.collectText(any())).thenReturn(ChannelDriver.TextResult.collected("Só queria saber o horário."));
        when(conversationService.converse(any(), anyList(), eq("Só queria saber o horário."), eq("fake-key")))
                .thenReturn(new ConversationResult("Funcionamos das 8h às 18h.", true, 50, 20, new BigDecimal("0.001")));
        var n = node("1");
        var context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-1", driver);
        var graph = new FlowGraph(2, List.of(n), List.of(new FlowGraph.Edge("e1", "n1", "fim")));

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isPresent();
        assertThat(edge.get().target()).isEqualTo("fim");
        verify(driver).playMessage(null, "Funcionamos das 8h às 18h.");
        verify(driver, never()).transferToQueue(any());
    }

    @Test
    @DisplayName("maxTurns atingido sem conclusão natural: escala para o fallback")
    void maxTurnsSemConclusao_escala() throws Exception {
        var fallback = CcQueue.builder().id(9L).name("5099").build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent(2, new BigDecimal("10"), fallback)));
        when(driver.collectText(any())).thenReturn(ChannelDriver.TextResult.collected("continua"));
        when(conversationService.converse(any(), anyList(), any(), eq("fake-key")))
                .thenReturn(new ConversationResult("Pode detalhar mais?", false, 50, 20, new BigDecimal("0.001")));
        var n = node("1");
        var context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-1", driver);
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(conversationService, times(2)).converse(any(), anyList(), any(), eq("fake-key"));
        verify(driver).transferToQueue("5099");
    }

    @Test
    @DisplayName("custo acumulado ultrapassa maxCostUsd: escala antes de atingir maxTurns")
    void custoUltrapassaTeto_escalaCedo() throws Exception {
        var fallback = CcQueue.builder().id(9L).name("5099").build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent(5, new BigDecimal("0.001"), fallback)));
        when(driver.collectText(any())).thenReturn(ChannelDriver.TextResult.collected("continua"));
        when(conversationService.converse(any(), anyList(), any(), eq("fake-key")))
                .thenReturn(new ConversationResult("resposta", false, 500, 500, new BigDecimal("0.01")));
        var n = node("1");
        var context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-1", driver);
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(conversationService, times(1)).converse(any(), anyList(), any(), eq("fake-key"));
        verify(driver).transferToQueue("5099");
    }

    @Test
    @DisplayName("falha ao chamar o Gemini: escala em vez de deixar a conversa travada")
    void falhaNaGeracao_escala() throws Exception {
        var fallback = CcQueue.builder().id(9L).name("5099").build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent(5, new BigDecimal("1"), fallback)));
        when(driver.collectText(any())).thenReturn(ChannelDriver.TextResult.collected("oi"));
        when(conversationService.converse(any(), anyList(), any(), eq("fake-key")))
                .thenThrow(new IllegalStateException("Resposta vazia do Gemini"));
        var n = node("1");
        var context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-1", driver);
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).transferToQueue("5099");
    }
}
