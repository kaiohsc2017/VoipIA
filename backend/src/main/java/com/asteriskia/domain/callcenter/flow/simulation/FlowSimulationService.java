package com.asteriskia.domain.callcenter.flow.simulation;

import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import com.asteriskia.domain.callcenter.flow.CcFlowVersionRepository;
import com.asteriskia.domain.callcenter.flow.FlowStatus;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FlowSimulationService — simulador de fluxo em "dry-run" (Fase 5d do plano de fechamento
 * 5/7/9). Reusa os mesmos {@link NodeHandler} beans do motor real ({@link
 * com.asteriskia.domain.callcenter.flow.engine.FlowExecutionEngine}), com a mesma convenção de
 * grafo/roteamento — mas roda seu próprio laço de execução, sem tocar
 * {@code FlowExecutionTraceService}: nenhuma simulação grava linha em {@code cc_flow_executions}/
 * {@code cc_flow_execution_steps} (tabelas de produção, particionadas por mês desde a Fase 10).
 *
 * <p>Simula sempre a versão <b>DRAFT</b> atual do fluxo (o operador testa o que está editando
 * antes de publicar) — se não houver rascunho (estado inconsistente, nunca deveria acontecer dado
 * o invariante de {@code CallCenterFlowService}), cai para a versão PUBLISHED como último recurso.
 *
 * <p><b>Nós de IA em modo seco</b>: os handlers reais de {@code consultar_base} (Fase 25, RAG) e
 * {@code pesquisa_satisfacao} (Fase 21, pode chamar Gemini de forma assíncrona) são sempre
 * substituídos por {@link DryRunConsultarBaseNodeHandler}/{@link DryRunSurveyNodeHandler} nesta
 * simulação, não importa o que vier na lista de handlers injetada — nenhuma chamada de IA real
 * pode ocorrer aqui, senão "Simular" em loop vira custo de IA de verdade.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowSimulationService {

    /** Mesmo guard anti-ciclo-infinito do motor real. */
    private static final int MAX_STEPS = 200;

    /** Teto de tamanho do corpo da requisição (achado de revisão — sem isso, uma quantidade
     * grande de respostas/variáveis simuladas infla o roteiro devolvido sem limite). */
    private static final int MAX_ENTRIES = 200;

    private static final int MAX_VALUE_LENGTH = 4096;

    private final CcFlowRepository flowRepository;
    private final CcFlowVersionRepository flowVersionRepository;
    private final List<NodeHandler> handlers;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public FlowSimulationResult simulate(Long flowId, FlowSimulationRequest request) {
        validateRequestSize(request);

        var flow =
                flowRepository
                        .findById(flowId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Fluxo não encontrado: " + flowId));

        var version =
                flowVersionRepository
                        .findByFlowIdAndStatus(flowId, FlowStatus.DRAFT)
                        .or(() -> flow.getPublishedVersionId() == null
                                ? Optional.empty()
                                : flowVersionRepository.findById(flow.getPublishedVersionId()))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT,
                                                "Fluxo " + flowId + " sem rascunho nem versão publicada para simular."));

        FlowGraph graph;
        try {
            graph = FlowGraph.parse(objectMapper, version.getGraph());
        } catch (Exception e) {
            log.warn("Grafo inválido na versão {} do fluxo {} durante simulação.", version.getId(), flowId, e);
            return new FlowSimulationResult(
                    flowId, version.getId(), version.getStatus().name(), "GRAFO_INVALIDO", List.of(), Map.of());
        }

        var startNode = graph.findStartNode();
        if (startNode.isEmpty()) {
            return new FlowSimulationResult(
                    flowId, version.getId(), version.getStatus().name(), "SEM_NO_INICIO", List.of(), Map.of());
        }

        var driver = new SimulatedChannelDriver(request.variaveis(), request.respostasSimuladas());
        var channelId = "sim-" + UUID.randomUUID();
        var context = new FlowExecutionContext(flowId, version.getId(), null, channelId, driver);
        var handlersByType = buildDryRunHandlerMap();

        var steps = new ArrayList<FlowSimulationStepView>();
        var currentNode = startNode.get();
        String outcome = "ERROR";
        int stepCount = 0;

        while (stepCount < MAX_STEPS) {
            stepCount++;
            var handler = handlersByType.get(currentNode.data().nodeType());
            if (handler == null) {
                steps.add(
                        new FlowSimulationStepView(
                                currentNode.id(),
                                currentNode.data().nodeType(),
                                currentNode.data().label(),
                                "Sem handler implementado para este tipo de nó — simulação interrompida.",
                                null));
                outcome = "SEM_HANDLER";
                break;
            }

            var eventsBefore = driver.eventLog().size();
            Optional<FlowGraph.Edge> edgeOpt;
            try {
                edgeOpt = handler.handle(graph, currentNode, context);
            } catch (Exception e) {
                log.warn("Falha ao simular o nó {} (tipo {}) do fluxo {}.", currentNode.id(), currentNode.data().nodeType(), flowId, e);
                steps.add(
                        new FlowSimulationStepView(
                                currentNode.id(),
                                currentNode.data().nodeType(),
                                currentNode.data().label(),
                                // Nunca expõe e.getMessage() ao cliente (padrão de segurança do projeto,
                                // ver rules/java/security.md) — o detalhe completo já foi logado acima.
                                "Erro interno durante a simulação — ver logs do servidor.",
                                null));
                outcome = "ERROR";
                break;
            }

            var novosEventos = driver.eventLog().subList(eventsBefore, driver.eventLog().size());
            steps.add(
                    new FlowSimulationStepView(
                            currentNode.id(),
                            currentNode.data().nodeType(),
                            currentNode.data().label(),
                            String.join(" | ", novosEventos),
                            edgeOpt.map(FlowGraph.Edge::id).orElse(null)));

            if (edgeOpt.isEmpty()) {
                outcome = terminalOutcome(currentNode.data().nodeType(), driver);
                break;
            }
            var nextNode = graph.findNode(edgeOpt.get().target());
            if (nextNode.isEmpty()) {
                steps.add(
                        new FlowSimulationStepView(
                                edgeOpt.get().target(), null, null, "Aresta aponta para nó inexistente no grafo.", null));
                outcome = "ERROR";
                break;
            }
            currentNode = nextNode.get();
        }

        if (stepCount >= MAX_STEPS) {
            outcome = "LIMITE_PASSOS_EXCEDIDO";
        }

        return new FlowSimulationResult(
                flowId, version.getId(), version.getStatus().name(), outcome, List.copyOf(steps), driver.variablesSnapshot());
    }

    private void validateRequestSize(FlowSimulationRequest request) {
        if (request.variaveis().size() > MAX_ENTRIES || request.respostasSimuladas().size() > MAX_ENTRIES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Máximo de " + MAX_ENTRIES + " entradas por lista/mapa na simulação.");
        }
        var valoresGrandes =
                request.variaveis().values().stream().anyMatch(v -> v != null && v.length() > MAX_VALUE_LENGTH)
                        || request.respostasSimuladas().stream().anyMatch(v -> v != null && v.length() > MAX_VALUE_LENGTH);
        if (valoresGrandes) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Cada valor simulado deve ter no máximo " + MAX_VALUE_LENGTH + " caracteres.");
        }
    }

    private String terminalOutcome(String nodeType, SimulatedChannelDriver driver) {
        if (driver.transferredQueue() != null) {
            return "TRANSFERRED_QUEUE";
        }
        return switch (nodeType) {
            case "encerrar" -> "COMPLETED";
            default -> "ABANDONED";
        };
    }

    /** Mapa de handlers por tipo, com os nós de IA sempre substituídos pela versão em modo seco —
     * ver javadoc da classe. */
    private Map<String, NodeHandler> buildDryRunHandlerMap() {
        var map = new HashMap<>(handlers.stream().collect(Collectors.toMap(NodeHandler::nodeType, h -> h, (a, b) -> a)));
        map.put("consultar_base", new DryRunConsultarBaseNodeHandler());
        map.put("pesquisa_satisfacao", new DryRunSurveyNodeHandler());
        return map;
    }
}
