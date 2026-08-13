package com.asteriskia.domain.callcenter.flow.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FlowGraph — leitura, pelo motor de execução (Fase 5b), do JSON nativo do React Flow persistido
 * pela 5a ({@code {schemaVersion, nodes, edges}}). Nunca escreve o grafo — só o interpreta.
 *
 * <p><b>Convenção de ramificação</b>: como o editor visual (5a) usa um único handle de saída por
 * nó ({@code GenericNode}), várias arestas podem partir do mesmo nó sem um identificador de handle
 * que as distinga. Para nós com mais de uma saída ({@code condicao}, {@code menu_opcoes}), a
 * escolha de qual aresta seguir é responsabilidade do {@code NodeHandler} de cada tipo — o
 * {@code menu_opcoes} mapeia explicitamente dígito→id-da-aresta via a propriedade {@code opcoes};
 * o {@code condicao} usa a ordem determinística das arestas de saída (ordenadas por id): a
 * primeira é o ramo "verdadeiro", a segunda o ramo "falso" (documentado também no handler).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowGraph(int schemaVersion, List<Node> nodes, List<Edge> edges) {

    public static FlowGraph parse(ObjectMapper mapper, String json) throws Exception {
        return mapper.readValue(json, FlowGraph.class);
    }

    public Optional<Node> findNode(String nodeId) {
        return nodes.stream().filter(n -> n.id().equals(nodeId)).findFirst();
    }

    public Optional<Node> findStartNode() {
        return nodes.stream().filter(n -> "inicio".equals(n.data().nodeType())).findFirst();
    }

    /** Arestas que partem de um nó, em ordem determinística (por id) — ver convenção da classe. */
    public List<Edge> outgoingEdges(String nodeId) {
        return edges.stream()
                .filter(e -> e.source().equals(nodeId))
                .sorted(Comparator.comparing(Edge::id))
                .toList();
    }

    /** Aresta de saída de um nó com o {@code sourceHandle} exato (schemaVersion 2 — Fase 5c). */
    public Optional<Edge> outgoingEdge(String nodeId, String sourceHandle) {
        return edges.stream()
                .filter(e -> e.source().equals(nodeId) && sourceHandle.equals(e.sourceHandle()))
                .findFirst();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String id, String type, NodeData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeData(String nodeType, String label, Map<String, Object> properties) {
        public String property(String name) {
            var value = properties == null ? null : properties.get(name);
            return value == null ? null : value.toString();
        }
    }

    /**
     * {@code sourceHandle} é {@code null} em grafos {@code schemaVersion 1} (handle único por nó,
     * ver convenção da classe) e vem preenchido pelo editor a partir de {@code schemaVersion 2}
     * (ex.: {@code "opt-3"}, {@code "opt-timeout"}, {@code "opt-invalido"} para {@code menu_opcoes}
     * — Fase 5c).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(String id, String source, String target, String sourceHandle) {
        /** Compatibilidade com grafos/testes anteriores à Fase 5c, sem {@code sourceHandle}. */
        public Edge(String id, String source, String target) {
            this(id, source, target, null);
        }
    }
}
