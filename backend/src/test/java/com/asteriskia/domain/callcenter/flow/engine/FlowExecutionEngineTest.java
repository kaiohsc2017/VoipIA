package com.asteriskia.domain.callcenter.flow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.flow.audio.CallCenterAudioService;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import com.asteriskia.domain.callcenter.flow.CcFlowVersion;
import com.asteriskia.domain.callcenter.flow.CcFlowVersionRepository;
import com.asteriskia.domain.callcenter.flow.FlowStatus;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver.PromptResult;
import com.asteriskia.domain.callcenter.flow.engine.handlers.ConditionNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.HangupNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.MenuNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.PlayAudioNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.SendToQueueNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.SetVariableNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.StartNodeHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * FlowExecutionEngineTest — motor puro (Fase 5b), sem Asterisk/ARI real: percorre o grafo com um
 * {@link FakeChannelDriver} de teste, cobrindo os 7 handlers implementados, fallback de exceção
 * para fila, aresta para nó inexistente, e o limite de passos anti-ciclo-infinito.
 * {@code CcFlowExecutionRepository}/{@code CcFlowExecutionStepRepository} são mockados (Mockito) —
 * a persistência do traço em si é testada isoladamente noutra classe.
 */
@ExtendWith(MockitoExtension.class)
class FlowExecutionEngineTest {

    @Mock private CcFlowRepository flowRepository;
    @Mock private CcFlowVersionRepository flowVersionRepository;
    @Mock private CcQueueRepository queueRepository;
    @Mock private CcFlowExecutionRepository executionRepository;
    @Mock private CcFlowExecutionStepRepository stepRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FlowExecutionTraceService traceService;

    @BeforeEach
    void setUp() {
        var executionIds = new AtomicLong(1);
        var stepIds = new AtomicLong(1);
        lenient()
                .when(executionRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            CcFlowExecution e = inv.getArgument(0);
                            if (e.getId() == null) {
                                e.setId(executionIds.getAndIncrement());
                            }
                            return e;
                        });
        lenient()
                .when(stepRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            CcFlowExecutionStep s = inv.getArgument(0);
                            if (s.getId() == null) {
                                s.setId(stepIds.getAndIncrement());
                            }
                            return s;
                        });
        traceService = new FlowExecutionTraceService(executionRepository, stepRepository);
    }

    private FlowExecutionEngine newEngine() {
        List<NodeHandler> handlers =
                new ArrayList<>(
                        List.of(
                                new StartNodeHandler(),
                                new PlayAudioNodeHandler(mock(CallCenterAudioService.class)),
                                new MenuNodeHandler(objectMapper, mock(CallCenterAudioService.class)),
                                new ConditionNodeHandler(),
                                new SetVariableNodeHandler(),
                                new SendToQueueNodeHandler(queueRepository),
                                new HangupNodeHandler()));
        return new FlowExecutionEngine(flowRepository, flowVersionRepository, traceService, handlers, objectMapper);
    }

    private void stubFlow(String extension, String graphJson) {
        var flow = CcFlow.builder().id(1L).name("f-" + extension).entryExtension(extension).publishedVersionId(10L).build();
        var version = CcFlowVersion.builder().id(10L).flow(flow).versionNumber(1).status(FlowStatus.PUBLISHED).graph(graphJson).build();
        lenient().when(flowRepository.findByEntryExtension(extension)).thenReturn(Optional.of(flow));
        lenient().when(flowVersionRepository.findById(10L)).thenReturn(Optional.of(version));
    }

    @Test
    @DisplayName("início → menu (opção 1) → condição verdadeira → fila")
    void menuTrueCondition_transfersToQueue() {
        when(queueRepository.findById(1L)).thenReturn(Optional.of(CcQueue.builder().id(1L).name("5001").build()));
        stubFlow(
                "6001",
                graph(
                        "["
                                + node("n1", "inicio") + ","
                                + node("n2", "menu_opcoes", "{\"opcoes\":\"1=e2;2=e3\"}") + ","
                                + node("n4", "definir_variavel", "{\"variavel\":\"escolha\",\"valor\":\"1\"}") + ","
                                + node("n5", "condicao", "{\"expressao\":\"escolha==1\"}") + ","
                                + node("n6", "enviar_fila", "{\"filaId\":\"1\"}")
                                + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                                + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n4\"},"
                                + "{\"id\":\"e4\",\"source\":\"n4\",\"target\":\"n5\"},"
                                + "{\"id\":\"e5\",\"source\":\"n5\",\"target\":\"n6\"}]"));
        var driver = new FakeChannelDriver(List.of(PromptResult.chosen("1")));

        newEngine().start("chan-1", "6001", "chan-1", driver);

        assertThat(driver.transferredQueue).isEqualTo("5001");
        assertThat(driver.ended).isFalse();
    }

    @Test
    @DisplayName("início → menu (opção 2) → condição falsa → encerrar")
    void menuFalseCondition_hangsUp() {
        stubFlow(
                "6002",
                graph(
                        "["
                                + node("n1", "inicio") + ","
                                + node("n2", "menu_opcoes", "{\"opcoes\":\"1=e2a;2=e2b\"}") + ","
                                + node("n3", "definir_variavel", "{\"variavel\":\"escolha\",\"valor\":\"1\"}") + ","
                                + node("n3b", "definir_variavel", "{\"variavel\":\"escolha\",\"valor\":\"2\"}") + ","
                                + node("n5", "condicao", "{\"expressao\":\"escolha==1\"}") + ","
                                + node("n6", "enviar_fila", "{\"filaId\":\"1\"}") + ","
                                + node("n7", "encerrar")
                                + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                                + "{\"id\":\"e2a\",\"source\":\"n2\",\"target\":\"n3\"},"
                                + "{\"id\":\"e2b\",\"source\":\"n2\",\"target\":\"n3b\"},"
                                + "{\"id\":\"e3\",\"source\":\"n3\",\"target\":\"n5\"},"
                                + "{\"id\":\"e3b\",\"source\":\"n3b\",\"target\":\"n5\"},"
                                + "{\"id\":\"e5\",\"source\":\"n5\",\"target\":\"n6\"},"
                                + "{\"id\":\"e6\",\"source\":\"n5\",\"target\":\"n7\"}]"));
        var driver = new FakeChannelDriver(List.of(PromptResult.chosen("2")));

        newEngine().start("chan-2", "6002", "chan-2", driver);

        assertThat(driver.ended).isTrue();
        assertThat(driver.transferredQueue).isNull();
    }

    @Test
    @DisplayName("aresta apontando para nó inexistente encerra a chamada com segurança")
    void edgeToMissingNode_hangsUpSafely() {
        stubFlow("6003", graph("[" + node("n1", "inicio") + "]", "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n99\"}]"));
        var driver = new FakeChannelDriver(List.of());

        newEngine().start("chan-3", "6003", "chan-3", driver);

        assertThat(driver.ended).isTrue();
    }

    @Test
    @DisplayName("menu sem resposta (timeout) encerra a chamada — nunca deixa o canal preso no Stasis")
    void menuTimeout_hangsUp() {
        stubFlow(
                "6004",
                graph(
                        "[" + node("n1", "inicio") + "," + node("n2", "menu_opcoes", "{\"opcoes\":\"1=e2\"}") + "," + node("n3", "encerrar") + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\"}]"));
        var driver = new FakeChannelDriver(List.of(PromptResult.timeout()));

        newEngine().start("chan-4", "6004", "chan-4", driver);

        assertThat(driver.ended).isTrue();
        assertThat(driver.transferredQueue).isNull();
    }

    @Test
    @DisplayName("ciclo infinito é interrompido pelo limite de passos e encerra a chamada")
    void infiniteCycle_stopsAtStepLimit() {
        stubFlow(
                "6005",
                graph(
                        "["
                                + node("n1", "inicio") + ","
                                + node("n2", "definir_variavel", "{\"variavel\":\"x\",\"valor\":\"1\"}") + ","
                                + node("n3", "definir_variavel", "{\"variavel\":\"x\",\"valor\":\"2\"}")
                                + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                                + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\"},"
                                + "{\"id\":\"e3\",\"source\":\"n3\",\"target\":\"n2\"}]"));
        var driver = new FakeChannelDriver(List.of());

        newEngine().start("chan-5", "6005", "chan-5", driver);

        assertThat(driver.ended).isTrue();
    }

    @Test
    @DisplayName("exceção de handler aciona fallback para fila alcançável, se existir")
    void handlerException_fallsBackToReachableQueue() {
        when(queueRepository.findById(1L)).thenReturn(Optional.of(CcQueue.builder().id(1L).name("5001").build()));
        List<NodeHandler> handlers =
                List.of(new StartNodeHandler(), new ThrowingNodeHandler("tocar_audio"), new SendToQueueNodeHandler(queueRepository), new HangupNodeHandler());
        var engine = new FlowExecutionEngine(flowRepository, flowVersionRepository, traceService, handlers, objectMapper);
        stubFlow(
                "6006",
                graph(
                        "[" + node("n1", "inicio") + "," + node("n2", "tocar_audio") + "," + node("n3", "enviar_fila", "{\"filaId\":\"1\"}") + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\"}]"));
        var driver = new FakeChannelDriver(List.of());

        engine.start("chan-6", "6006", "chan-6", driver);

        assertThat(driver.transferredQueue).isEqualTo("5001");
    }

    @Test
    @DisplayName("exceção de handler sem fila alcançável encerra a chamada")
    void handlerException_noReachableQueue_hangsUp() {
        List<NodeHandler> handlers = List.of(new StartNodeHandler(), new ThrowingNodeHandler("tocar_audio"), new HangupNodeHandler());
        var engine = new FlowExecutionEngine(flowRepository, flowVersionRepository, traceService, handlers, objectMapper);
        stubFlow(
                "6007",
                graph(
                        "[" + node("n1", "inicio") + "," + node("n2", "tocar_audio") + "," + node("n3", "encerrar") + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\"}]"));
        var driver = new FakeChannelDriver(List.of());

        engine.start("chan-7", "6007", "chan-7", driver);

        assertThat(driver.ended).isTrue();
        assertThat(driver.transferredQueue).isNull();
    }

    @Test
    @DisplayName("fluxo sem versão publicada encerra a chamada imediatamente")
    void flowWithoutPublishedVersion_hangsUpImmediately() {
        var flow = CcFlow.builder().id(2L).name("f2").entryExtension("6008").publishedVersionId(null).build();
        when(flowRepository.findByEntryExtension("6008")).thenReturn(Optional.of(flow));
        var driver = new FakeChannelDriver(List.of());

        newEngine().start("chan-8", "6008", "chan-8", driver);

        assertThat(driver.ended).isTrue();
    }

    @Test
    @DisplayName("extensão sem fluxo cadastrado encerra a chamada imediatamente")
    void unknownExtension_hangsUpImmediately() {
        when(flowRepository.findByEntryExtension("6009")).thenReturn(Optional.empty());
        var driver = new FakeChannelDriver(List.of());

        newEngine().start("chan-9", "6009", "chan-9", driver);

        assertThat(driver.ended).isTrue();
    }

    private static String graph(String nodesJson, String edgesJson) {
        return "{\"schemaVersion\":1,\"nodes\":" + nodesJson + ",\"edges\":" + edgesJson + "}";
    }

    /**
     * Nó no formato real persistido pela UI: {@code type} é sempre "generic" (renderização do
     * React Flow) — o tipo de domínio vai em {@code data.nodeType}, mesmo bug encontrado e
     * corrigido no {@code FlowGraphValidator} nesta sub-fase.
     */
    private static String node(String id, String nodeType, String propertiesJson) {
        return "{\"id\":\""
                + id
                + "\",\"type\":\"generic\",\"data\":{\"nodeType\":\""
                + nodeType
                + "\",\"properties\":"
                + propertiesJson
                + "}}";
    }

    private static String node(String id, String nodeType) {
        return node(id, nodeType, "{}");
    }

    /** Handler substituto que sempre lança — usado só para testar o fallback de exceção do motor. */
    private static final class ThrowingNodeHandler implements NodeHandler {
        private final String type;

        ThrowingNodeHandler(String type) {
            this.type = type;
        }

        @Override
        public String nodeType() {
            return type;
        }

        @Override
        public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
            throw new RuntimeException("falha simulada");
        }
    }

    /** ChannelDriver de teste — sem Asterisk/ARI, respostas de promptChoice pré-programadas em fila. */
    private static final class FakeChannelDriver implements ChannelDriver {
        private final ArrayDeque<PromptResult> scriptedResults;
        private final HashMap<String, String> variables = new HashMap<>();
        boolean ended = false;
        String transferredQueue = null;

        FakeChannelDriver(List<PromptResult> scriptedResults) {
            this.scriptedResults = new ArrayDeque<>(scriptedResults);
        }

        @Override
        public void playMessage(String audioPath, String text) {}

        @Override
        public PromptResult promptChoice(List<String> validChoices, Duration timeout) {
            return scriptedResults.isEmpty() ? PromptResult.timeout() : scriptedResults.poll();
        }

        @Override
        public RecordResult recordResponse(Duration maxDuration) {
            return RecordResult.hungUp();
        }

        @Override
        public TextResult collectText(Duration timeout) {
            return TextResult.hungUp();
        }

        @Override
        public void setVariable(String name, String value) {
            variables.put(name, value);
        }

        @Override
        public String getVariable(String name) {
            return variables.get(name);
        }

        @Override
        public void transferToQueue(String queueExtension) {
            transferredQueue = queueExtension;
        }

        @Override
        public void end() {
            ended = true;
        }
    }
}
