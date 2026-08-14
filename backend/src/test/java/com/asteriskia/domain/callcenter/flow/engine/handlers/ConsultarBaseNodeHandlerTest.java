package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.kb.CallCenterKbAnswerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsultarBaseNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CallCenterKbAnswerService answerService;
    private CcQueueRepository queueRepository;
    private ConsultarBaseNodeHandler handler;
    private ChannelDriver driver;
    private FlowExecutionContext context;

    @BeforeEach
    void setUp() {
        answerService = mock(CallCenterKbAnswerService.class);
        queueRepository = mock(CcQueueRepository.class);
        handler = new ConsultarBaseNodeHandler(answerService, queueRepository);
        driver = mock(ChannelDriver.class);
        context = new FlowExecutionContext(1L, 1L, 1L, "chat-session-42", driver);
        context.setVariable("pergunta", "Qual o horário de funcionamento?");
    }

    private FlowGraph.Node node(String filaId) throws Exception {
        var props = new java.util.LinkedHashMap<String, Object>();
        if (filaId != null) props.put("filaId", filaId);
        var json =
                "{\"id\":\"n1\",\"type\":\"generic\",\"data\":{\"nodeType\":\"consultar_base\",\"properties\":"
                        + mapper.writeValueAsString(props) + "}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    @Test
    @DisplayName("resposta encontrada: envia a mensagem e segue a primeira aresta")
    void matched_playsMessageAndFollowsFirstEdge() throws Exception {
        when(answerService.answer(eq(42L), any())).thenReturn(new CallCenterKbAnswerService.AnswerResult(true, "resposta com base no artigo"));
        var n = node(null);
        var graph =
                new FlowGraph(2, List.of(n), List.of(new FlowGraph.Edge("e1", "n1", "matched"), new FlowGraph.Edge("e2", "n1", "escalate")));

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isPresent();
        assertThat(edge.get().target()).isEqualTo("matched");
        verify(driver).playMessage(null, "resposta com base no artigo");
        verify(driver, never()).transferToQueue(any());
    }

    @Test
    @DisplayName("sem resposta com segunda aresta configurada: segue o ramo de escalonamento sem chamar fila de fallback")
    void notMatched_withSecondEdge_followsIt() throws Exception {
        when(answerService.answer(eq(42L), any())).thenReturn(new CallCenterKbAnswerService.AnswerResult(false, null));
        var n = node("99");
        var graph =
                new FlowGraph(2, List.of(n), List.of(new FlowGraph.Edge("e1", "n1", "matched"), new FlowGraph.Edge("e2", "n1", "escalate")));

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isPresent();
        assertThat(edge.get().target()).isEqualTo("escalate");
        verify(driver, never()).transferToQueue(any());
        verify(queueRepository, never()).findById(any());
    }

    @Test
    @DisplayName("sem resposta e sem segunda aresta: escala para a fila de fallback configurada")
    void notMatched_withoutSecondEdge_transfersToFallbackQueue() throws Exception {
        when(answerService.answer(eq(42L), any())).thenReturn(new CallCenterKbAnswerService.AnswerResult(false, null));
        when(queueRepository.findById(99L)).thenReturn(Optional.of(CcQueue.builder().id(99L).name("5099").build()));
        var n = node("99");
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).transferToQueue("5099");
    }

    @Test
    @DisplayName("sem resposta, sem segunda aresta e sem fila de fallback: encerra em vez de travar a conversa")
    void notMatched_withoutFallback_endsSafely() throws Exception {
        when(answerService.answer(eq(42L), any())).thenReturn(new CallCenterKbAnswerService.AnswerResult(false, null));
        var n = node(null);
        var graph = new FlowGraph(2, List.of(n), List.of());

        var edge = handler.handle(graph, n, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
        verify(driver, never()).transferToQueue(any());
    }

    @Test
    @DisplayName("channelId fora do padrão chat-session-* encerra em vez de tentar interpretar como sessão de chat")
    void channelIdForaDoPadrao_endsSafely() {
        var voiceContext = new FlowExecutionContext(1L, 1L, 1L, "ari-channel-abc", driver);

        try {
            var n = node(null);
            var graph = new FlowGraph(2, List.of(n), List.of());
            var edge = handler.handle(graph, n, voiceContext);
            assertThat(edge).isEmpty();
            verify(driver).end();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
