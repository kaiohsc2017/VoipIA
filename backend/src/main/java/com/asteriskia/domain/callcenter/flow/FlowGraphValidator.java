package com.asteriskia.domain.callcenter.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * FlowGraphValidator — valida o grafo (JSON nativo do React Flow: {@code nodes}/{@code edges}) de
 * um fluxo (Fase 5a). Roda em dois níveis: ao salvar rascunho ({@code forPublish=false}, só
 * avisos) e ao publicar ({@code forPublish=true}, erros bloqueiam). A validação existe aqui — no
 * servidor — porque o grafo é entrada de usuário que vira instrução de execução; validar só no
 * editor React seria contornável via chamada direta à API.
 */
@Component
@RequiredArgsConstructor
public class FlowGraphValidator {

    private static final String NODE_TYPE_INICIO = "inicio";
    private static final String CHANNEL_BOTH = "both";

    private final ObjectMapper objectMapper;
    private final FlowGraphNodeCatalog nodeCatalog;

    public FlowGraphValidationResult validate(String graphJson, String flowChannel, boolean forPublish) {
        List<FlowGraphValidationResult.Issue> errors = new ArrayList<>();
        List<FlowGraphValidationResult.Issue> warnings = new ArrayList<>();

        JsonNode root;
        try {
            root = objectMapper.readTree(graphJson);
        } catch (Exception e) {
            errors.add(new FlowGraphValidationResult.Issue(null, "Grafo em formato JSON inválido."));
            return new FlowGraphValidationResult(errors, warnings);
        }

        var nodes = root.path("nodes");
        var edges = root.path("edges");

        if (!nodes.isArray() || nodes.isEmpty()) {
            errors.add(new FlowGraphValidationResult.Issue(null, "Grafo vazio: nenhum nó definido."));
            return new FlowGraphValidationResult(errors, warnings);
        }

        Map<String, String> nodeTypesById = new HashMap<>();
        for (JsonNode node : nodes) {
            String id = node.path("id").asText(null);
            // O "type" no topo do nó é o tipo de RENDERIZAÇÃO do React Flow (sempre "generic" no
            // grafo real persistido pela UI, ver FlowEditor.tsx) — o tipo de domínio do catálogo
            // vem em data.nodeType. Ler "type" aqui faria todo nó real virar "tipo desconhecido".
            String type = node.path("data").path("nodeType").asText(null);
            if (id != null) {
                nodeTypesById.put(id, type);
            }
        }

        validateStartNode(nodeTypesById, errors);
        validateNodeTypes(nodeTypesById, flowChannel, forPublish, errors);

        Set<String> nodesWithIncomingEdge = new HashSet<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        if (edges.isArray()) {
            for (JsonNode edge : edges) {
                String source = edge.path("source").asText(null);
                String target = edge.path("target").asText(null);
                String edgeId = edge.path("id").asText(null);
                if (source == null || target == null) {
                    continue;
                }
                if (!nodeTypesById.containsKey(source) || !nodeTypesById.containsKey(target)) {
                    errors.add(
                            new FlowGraphValidationResult.Issue(
                                    edgeId, "Aresta aponta para nó inexistente (" + source + " -> " + target + ")."));
                    continue;
                }
                nodesWithIncomingEdge.add(target);
                adjacency.computeIfAbsent(source, k -> new ArrayList<>()).add(target);
            }
        }

        validateOrphanNodes(nodeTypesById, nodesWithIncomingEdge, errors);
        detectCycle(adjacency, warnings);

        return new FlowGraphValidationResult(errors, warnings);
    }

    private void validateStartNode(Map<String, String> nodeTypesById, List<FlowGraphValidationResult.Issue> errors) {
        long startCount = nodeTypesById.values().stream().filter(NODE_TYPE_INICIO::equals).count();
        if (startCount == 0) {
            errors.add(new FlowGraphValidationResult.Issue(null, "O fluxo precisa de um nó de início."));
        } else if (startCount > 1) {
            errors.add(new FlowGraphValidationResult.Issue(null, "O fluxo não pode ter mais de um nó de início."));
        }
    }

    private void validateNodeTypes(
            Map<String, String> nodeTypesById,
            String flowChannel,
            boolean forPublish,
            List<FlowGraphValidationResult.Issue> errors) {
        for (var entry : nodeTypesById.entrySet()) {
            String nodeId = entry.getKey();
            String type = entry.getValue();
            var catalogEntry = type == null ? java.util.Optional.<FlowGraphNodeType>empty() : nodeCatalog.findByType(type);
            if (catalogEntry.isEmpty()) {
                errors.add(new FlowGraphValidationResult.Issue(nodeId, "Tipo de nó desconhecido: " + type));
                continue;
            }
            var nodeType = catalogEntry.get();
            if (!CHANNEL_BOTH.equals(nodeType.channel()) && !nodeType.channel().equals(flowChannel)) {
                errors.add(
                        new FlowGraphValidationResult.Issue(
                                nodeId,
                                "Nó \""
                                        + nodeType.label()
                                        + "\" é exclusivo do canal "
                                        + nodeType.channel()
                                        + ", incompatível com o canal do fluxo ("
                                        + flowChannel
                                        + ")."));
            }
            if (forPublish && !nodeType.implementado()) {
                errors.add(
                        new FlowGraphValidationResult.Issue(
                                nodeId,
                                "Nó \"" + nodeType.label() + "\" ainda não é executado pelo motor de fluxo."));
            }
        }
    }

    private void validateOrphanNodes(
            Map<String, String> nodeTypesById,
            Set<String> nodesWithIncomingEdge,
            List<FlowGraphValidationResult.Issue> errors) {
        for (var entry : nodeTypesById.entrySet()) {
            String nodeId = entry.getKey();
            String type = entry.getValue();
            if (NODE_TYPE_INICIO.equals(type)) {
                continue;
            }
            if (!nodesWithIncomingEdge.contains(nodeId)) {
                errors.add(new FlowGraphValidationResult.Issue(nodeId, "Nó órfão: nenhuma aresta chega até ele."));
            }
        }
    }

    /** Ciclo simples é só aviso nesta sub-fase (sem motor de execução ainda — ver Fase 5b). */
    private void detectCycle(Map<String, List<String>> adjacency, List<FlowGraphValidationResult.Issue> warnings) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String start : adjacency.keySet()) {
            if (!visited.contains(start) && hasCycle(start, adjacency, visited, inStack)) {
                warnings.add(new FlowGraphValidationResult.Issue(null, "O grafo contém um ciclo."));
                return;
            }
        }
    }

    private boolean hasCycle(
            String node, Map<String, List<String>> adjacency, Set<String> visited, Set<String> inStack) {
        visited.add(node);
        inStack.add(node);
        for (String next : adjacency.getOrDefault(node, List.of())) {
            if (inStack.contains(next)) {
                return true;
            }
            if (!visited.contains(next) && hasCycle(next, adjacency, visited, inStack)) {
                return true;
            }
        }
        inStack.remove(node);
        return false;
    }
}
