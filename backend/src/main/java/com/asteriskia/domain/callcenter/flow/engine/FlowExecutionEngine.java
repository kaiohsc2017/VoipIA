package com.asteriskia.domain.callcenter.flow.engine;

import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import com.asteriskia.domain.callcenter.flow.CcFlowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * FlowExecutionEngine — núcleo do motor de execução do Flow Builder (Fase 5b). Interpreta o
 * grafo da versão <b>PUBLISHED</b> do fluxo (nunca DRAFT), fixada para toda a duração da chamada —
 * mesmo que uma nova versão seja publicada no meio, esta execução continua na versão com que
 * começou. Todo {@link NodeHandler} roda protegido: uma exceção tenta um caminho de fuga para um
 * nó "enviar_fila" alcançável a partir do nó atual; se não houver, encerra a chamada — nunca deixa
 * o canal preso dentro do Stasis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowExecutionEngine {

    /** Guarda contra ciclo infinito em runtime — a validação estática da 5a cobre só o caso simples. */
    private static final int MAX_STEPS = 200;

    private final CcFlowRepository flowRepository;
    private final CcFlowVersionRepository flowVersionRepository;
    private final FlowExecutionTraceService traceService;
    private final List<NodeHandler> handlers;
    private final ObjectMapper objectMapper;

    private final Map<String, FlowExecutionContext> activeByChannelId = new ConcurrentHashMap<>();
    private Map<String, NodeHandler> handlersByType;

    private Map<String, NodeHandler> handlersByType() {
        if (handlersByType == null) {
            handlersByType = handlers.stream().collect(Collectors.toMap(NodeHandler::nodeType, h -> h));
        }
        return handlersByType;
    }

    /** Início de uma execução — chamado pelo {@code AriEventListener} ao receber {@code StasisStart}. */
    public void start(String channelId, String extension, String channelUniqueId, ChannelDriver driver) {
        var flowOpt = flowRepository.findByEntryExtension(extension);
        if (flowOpt.isEmpty()) {
            log.warn("Fluxo sem publicação para a extensão {} — encerrando chamada {}.", extension, channelId);
            driver.end();
            return;
        }
        startResolved(flowOpt.get(), channelId, channelUniqueId, driver);
    }

    /** Início de uma execução resolvendo o fluxo direto pelo id — usado pelo canal de chat (Fase
     * 24, {@code ChatFlowLauncherService}), que não tem "extensão" nenhuma para resolver contra
     * {@code entryExtension} (exclusivo do canal voz). */
    public void startForFlow(Long flowId, String channelId, String channelUniqueId, ChannelDriver driver) {
        var flowOpt = flowRepository.findById(flowId);
        if (flowOpt.isEmpty()) {
            log.warn("Fluxo {} inexistente — encerrando sessão de chat {}.", flowId, channelId);
            driver.end();
            return;
        }
        startResolved(flowOpt.get(), channelId, channelUniqueId, driver);
    }

    private void startResolved(CcFlow flow, String channelId, String channelUniqueId, ChannelDriver driver) {
        if (flow.getPublishedVersionId() == null) {
            log.warn("Fluxo {} sem versão publicada — encerrando canal {}.", flow.getId(), channelId);
            driver.end();
            return;
        }
        var versionOpt = flowVersionRepository.findById(flow.getPublishedVersionId());
        if (versionOpt.isEmpty()) {
            log.error(
                    "Fluxo {} aponta para versão publicada {} inexistente — encerrando chamada {}.",
                    flow.getId(),
                    flow.getPublishedVersionId(),
                    channelId);
            driver.end();
            return;
        }
        var version = versionOpt.get();

        FlowGraph graph;
        try {
            graph = FlowGraph.parse(objectMapper, version.getGraph());
        } catch (Exception e) {
            log.error("Grafo inválido na versão {} do fluxo {} — encerrando chamada {}.", version.getId(), flow.getId(), channelId, e);
            driver.end();
            return;
        }
        var startNode = graph.findStartNode();
        if (startNode.isEmpty()) {
            log.error("Fluxo {} versão {} sem nó início — encerrando chamada {}.", flow.getId(), version.getId(), channelId);
            driver.end();
            return;
        }

        var execution = traceService.startExecution(flow, version, channelId, channelUniqueId);
        var context = new FlowExecutionContext(flow.getId(), version.getId(), execution.getId(), channelId, driver);
        activeByChannelId.put(channelId, context);

        try {
            run(graph, startNode.get(), context, execution);
        } finally {
            activeByChannelId.remove(channelId);
        }
    }

    /**
     * Driver da execução em curso deste canal, se houver — usado pelo {@code AriEventListener}
     * para repassar {@code ChannelDtmfReceived} ao driver real (a espera do dígito, dentro de
     * {@code promptChoice}, é resolvida pelo próprio driver, não pelo motor).
     */
    public Optional<ChannelDriver> driverFor(String channelId) {
        return Optional.ofNullable(activeByChannelId.get(channelId)).map(FlowExecutionContext::driver);
    }

    /** Marca a execução deste canal como abandonada (usado pelo {@code AriEventListener} em {@code StasisEnd}). */
    public void onChannelEnded(String channelId) {
        activeByChannelId.remove(channelId);
    }

    public boolean hasActiveExecution(String channelId) {
        return activeByChannelId.containsKey(channelId);
    }

    private void run(FlowGraph graph, FlowGraph.Node startNode, FlowExecutionContext context, CcFlowExecution execution) {
        var currentNode = startNode;
        String outcome = "ERROR";
        String lastNodeId = currentNode.id();
        int steps = 0;

        while (steps < MAX_STEPS) {
            steps++;
            lastNodeId = currentNode.id();
            var handler = handlersByType().get(currentNode.data().nodeType());
            if (handler == null) {
                log.error(
                        "Nó {} (tipo {}) sem handler implementado durante execução real — encerrando chamada {}.",
                        currentNode.id(),
                        currentNode.data().nodeType(),
                        context.channelId());
                break;
            }

            var step = traceService.enterStep(execution, currentNode.id(), currentNode.data().nodeType(), null);
            Optional<FlowGraph.Edge> edgeOpt;
            try {
                edgeOpt = handler.handle(graph, currentNode, context);
            } catch (Exception e) {
                log.error(
                        "Falha ao executar nó {} (tipo {}) do fluxo {} — tentando caminho de fuga para fila.",
                        currentNode.id(),
                        currentNode.data().nodeType(),
                        context.flowId(),
                        e);
                traceService.exitStep(step, null);
                outcome = attemptQueueFallback(graph, currentNode.id(), context, execution) ? "TRANSFERRED_QUEUE" : "ERROR";
                if ("ERROR".equals(outcome)) {
                    context.driver().end();
                }
                break;
            }
            traceService.exitStep(step, edgeOpt.map(FlowGraph.Edge::id).orElse(null));

            if (edgeOpt.isEmpty()) {
                outcome = terminalOutcome(currentNode.data().nodeType());
                break;
            }
            var nextNode = graph.findNode(edgeOpt.get().target());
            if (nextNode.isEmpty()) {
                log.error(
                        "Aresta {} do fluxo {} aponta para nó inexistente — encerrando chamada {}.",
                        edgeOpt.get().id(),
                        context.flowId(),
                        context.channelId());
                context.driver().end();
                outcome = "ERROR";
                break;
            }
            currentNode = nextNode.get();
        }

        if (steps >= MAX_STEPS) {
            log.error("Fluxo {} excedeu o limite de {} passos — encerrando chamada {}.", context.flowId(), MAX_STEPS, context.channelId());
            context.driver().end();
        }
        traceService.endExecution(execution, outcome, lastNodeId);
    }

    private String terminalOutcome(String nodeType) {
        return switch (nodeType) {
            case "enviar_fila" -> "TRANSFERRED_QUEUE";
            case "encerrar" -> "COMPLETED";
            default -> "ABANDONED";
        };
    }

    /** BFS a partir do nó atual em busca do "enviar_fila" mais próximo — caminho de fuga seguro. */
    private boolean attemptQueueFallback(FlowGraph graph, String fromNodeId, FlowExecutionContext context, CcFlowExecution execution) {
        var queueHandler = handlersByType().get("enviar_fila");
        if (queueHandler == null) {
            return false;
        }
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(fromNodeId);
        visited.add(fromNodeId);
        while (!queue.isEmpty()) {
            var nodeId = queue.poll();
            var node = graph.findNode(nodeId);
            if (node.isPresent() && "enviar_fila".equals(node.get().data().nodeType())) {
                try {
                    var step = traceService.enterStep(execution, node.get().id(), "enviar_fila", "fallback");
                    queueHandler.handle(graph, node.get(), context);
                    traceService.exitStep(step, null);
                    return true;
                } catch (Exception e) {
                    log.error("Caminho de fuga para fila também falhou no fluxo {}.", context.flowId(), e);
                    return false;
                }
            }
            for (var edge : graph.outgoingEdges(nodeId)) {
                if (visited.add(edge.target())) {
                    queue.add(edge.target());
                }
            }
        }
        return false;
    }

    Set<String> activeChannelIds() {
        return Set.copyOf(activeByChannelId.keySet());
    }
}
