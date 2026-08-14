package com.asteriskia.domain.callcenter.flow.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import com.asteriskia.domain.callcenter.flow.CcFlowVersion;
import com.asteriskia.domain.callcenter.flow.CcFlowVersionRepository;
import com.asteriskia.domain.callcenter.flow.FlowStatus;
import com.asteriskia.domain.callcenter.flow.audio.CallCenterAudioService;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.ConditionNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.ConsultarBaseNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.HangupNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.MenuNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.PlayAudioNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.SendToQueueNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.SetVariableNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.StartNodeHandler;
import com.asteriskia.domain.callcenter.flow.engine.handlers.SurveyNodeHandler;
import com.asteriskia.domain.callcenter.kb.CallCenterKbAnswerService;
import com.asteriskia.domain.callcenter.nps.CallCenterSurveyRunner;
import com.asteriskia.domain.callcenter.nps.CcSurveyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * FlowSimulationServiceTest — cobre o simulador de fluxo (Fase 5d): roteamento fiel ao motor real
 * (mesmos handlers), nunca persiste nada (não há repositório de execução/passo injetado — se o
 * serviço tentasse persistir, o teste nem compilaria), e — a defesa central desta fatia — nunca
 * chama os serviços de IA reais (KB/RAG da Fase 25, pesquisa de satisfação da Fase 21), mesmo
 * quando os handlers reais desses nós estão na lista injetada.
 */
@ExtendWith(MockitoExtension.class)
class FlowSimulationServiceTest {

    @Mock private CcFlowRepository flowRepository;
    @Mock private CcFlowVersionRepository flowVersionRepository;
    @Mock private CcQueueRepository queueRepository;
    @Mock private CallCenterKbAnswerService kbAnswerService;
    @Mock private CallCenterSurveyRunner surveyRunner;
    @Mock private CcSurveyRepository surveyRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FlowSimulationService newService(List<NodeHandler> extraHandlers) {
        var handlers = new java.util.ArrayList<NodeHandler>();
        handlers.add(new StartNodeHandler());
        handlers.add(new PlayAudioNodeHandler(mock(CallCenterAudioService.class)));
        handlers.add(new MenuNodeHandler(objectMapper, mock(CallCenterAudioService.class)));
        handlers.add(new ConditionNodeHandler());
        handlers.add(new SetVariableNodeHandler());
        handlers.add(new SendToQueueNodeHandler(queueRepository));
        handlers.add(new HangupNodeHandler());
        handlers.addAll(extraHandlers);
        return new FlowSimulationService(flowRepository, flowVersionRepository, handlers, objectMapper);
    }

    @BeforeEach
    void setUp() {
        lenient().when(flowRepository.findById(1L)).thenReturn(Optional.of(CcFlow.builder().id(1L).name("f1").build()));
    }

    private void stubDraft(String graphJson) {
        var version =
                CcFlowVersion.builder().id(100L).versionNumber(2).status(FlowStatus.DRAFT).graph(graphJson).build();
        when(flowVersionRepository.findByFlowIdAndStatus(1L, FlowStatus.DRAFT)).thenReturn(Optional.of(version));
    }

    private static String graph(String nodesJson, String edgesJson) {
        return "{\"schemaVersion\":1,\"nodes\":" + nodesJson + ",\"edges\":" + edgesJson + "}";
    }

    private static String node(String id, String nodeType, String propertiesJson) {
        return "{\"id\":\"" + id + "\",\"type\":\"generic\",\"data\":{\"nodeType\":\"" + nodeType + "\",\"properties\":" + propertiesJson + "}}";
    }

    private static String node(String id, String nodeType) {
        return node(id, nodeType, "{}");
    }

    @Test
    @DisplayName("simula início → menu (opção 1) → condição verdadeira → fila, sem persistir nada")
    void simulate_menuToQueue() {
        when(queueRepository.findById(1L)).thenReturn(Optional.of(CcQueue.builder().id(1L).name("5001").build()));
        stubDraft(
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

        var result =
                newService(List.of())
                        .simulate(1L, new FlowSimulationRequest(Map.of(), List.of("1")));

        assertThat(result.outcome()).isEqualTo("TRANSFERRED_QUEUE");
        assertThat(result.steps()).hasSize(5);
        assertThat(result.flowVersionId()).isEqualTo(100L);
        assertThat(result.versionStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("DEFESA CENTRAL: consultar_base em simulação nunca chama o serviço de IA/KB real")
    void simulate_consultarBase_neverCallsRealKbService() {
        var handler = new ConsultarBaseNodeHandler(kbAnswerService, queueRepository);
        stubDraft(
                graph(
                        "[" + node("n1", "inicio") + "," + node("n2", "consultar_base", "{\"variavelPergunta\":\"pergunta\"}") + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]"));

        var result = newService(List.of(handler)).simulate(1L, FlowSimulationRequest.empty());

        verify(kbAnswerService, never()).answer(any(), anyString());
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1).nodeType()).isEqualTo("consultar_base");
    }

    @Test
    @DisplayName("DEFESA CENTRAL: pesquisa_satisfacao em simulação nunca chama o runner de IA real")
    void simulate_pesquisaSatisfacao_neverCallsRealSurveyRunner() {
        var handler = new SurveyNodeHandler(surveyRepository, surveyRunner);
        stubDraft(
                graph(
                        "[" + node("n1", "inicio") + "," + node("n2", "pesquisa_satisfacao", "{\"pesquisaId\":\"1\"}") + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]"));

        newService(List.of(handler)).simulate(1L, FlowSimulationRequest.empty());

        verify(surveyRunner, never()).run(any(), any(), any(), any());
        verify(surveyRepository, never()).findById(any());
    }

    @Test
    @DisplayName("simulação sem rascunho nem versão publicada retorna 409")
    void simulate_noDraftNorPublished_throws409() {
        when(flowVersionRepository.findByFlowIdAndStatus(1L, FlowStatus.DRAFT)).thenReturn(Optional.empty());

        var thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ResponseStatusException.class, () -> newService(List.of()).simulate(1L, FlowSimulationRequest.empty()));

        assertThat(thrown.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("simulação de fluxo inexistente retorna 404")
    void simulate_unknownFlow_throws404() {
        when(flowRepository.findById(99L)).thenReturn(Optional.empty());

        var thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ResponseStatusException.class, () -> newService(List.of()).simulate(99L, FlowSimulationRequest.empty()));

        assertThat(thrown.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("grafo sem nó início retorna outcome SEM_NO_INICIO, sem lançar exceção")
    void simulate_noStartNode_returnsOutcome() {
        stubDraft(graph("[" + node("n2", "encerrar") + "]", "[]"));

        var result = newService(List.of()).simulate(1L, FlowSimulationRequest.empty());

        assertThat(result.outcome()).isEqualTo("SEM_NO_INICIO");
        assertThat(result.steps()).isEmpty();
    }

    @Test
    @DisplayName("menu sem resposta simulada disponível encerra a simulação (timeout), como cliente real que não responde")
    void simulate_menuWithoutScriptedResponse_endsAsAbandoned() {
        stubDraft(
                graph(
                        "[" + node("n1", "inicio") + "," + node("n2", "menu_opcoes", "{\"opcoes\":\"1=e2\"}") + "," + node("n3", "encerrar") + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\"}]"));

        var result = newService(List.of()).simulate(1L, FlowSimulationRequest.empty());

        assertThat(result.outcome()).isEqualTo("ABANDONED");
    }

    @Test
    @DisplayName("ciclo infinito no grafo é interrompido pelo limite de passos (LIMITE_PASSOS_EXCEDIDO)")
    void simulate_infiniteCycle_stopsAtStepLimit() {
        stubDraft(
                graph(
                        "["
                                + node("n1", "inicio") + ","
                                + node("n2", "definir_variavel", "{\"variavel\":\"x\",\"valor\":\"1\"}") + ","
                                + node("n3", "definir_variavel", "{\"variavel\":\"x\",\"valor\":\"2\"}")
                                + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                                + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\"},"
                                + "{\"id\":\"e3\",\"source\":\"n3\",\"target\":\"n2\"}]"));

        var result = newService(List.of()).simulate(1L, FlowSimulationRequest.empty());

        assertThat(result.outcome()).isEqualTo("LIMITE_PASSOS_EXCEDIDO");
    }

    @Test
    @DisplayName("aresta apontando para nó inexistente no grafo retorna outcome ERROR")
    void simulate_edgeToMissingNode_returnsError() {
        stubDraft(graph("[" + node("n1", "inicio") + "]", "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n99\"}]"));

        var result = newService(List.of()).simulate(1L, FlowSimulationRequest.empty());

        assertThat(result.outcome()).isEqualTo("ERROR");
        assertThat(result.steps()).last().extracting("detail").isEqualTo("Aresta aponta para nó inexistente no grafo.");
    }

    @Test
    @DisplayName("nó de tipo sem handler implementado retorna outcome SEM_HANDLER")
    void simulate_nodeWithoutHandler_returnsSemHandler() {
        stubDraft(
                graph(
                        "[" + node("n1", "inicio") + "," + node("n2", "agente_ia") + "]",
                        "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]"));

        var result = newService(List.of()).simulate(1L, FlowSimulationRequest.empty());

        assertThat(result.outcome()).isEqualTo("SEM_HANDLER");
    }

    @Test
    @DisplayName("requisição com respostas simuladas em excesso é rejeitada com 400, antes de tocar o banco")
    void simulate_tooManyScriptedResponses_throws400() {
        var respostas = java.util.Collections.nCopies(201, "x");

        var thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ResponseStatusException.class,
                        () -> newService(List.of()).simulate(1L, new FlowSimulationRequest(Map.of(), respostas)));

        assertThat(thrown.getStatusCode().value()).isEqualTo(400);
    }
}
