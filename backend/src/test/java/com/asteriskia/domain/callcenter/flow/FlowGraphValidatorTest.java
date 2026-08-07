package com.asteriskia.domain.callcenter.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FlowGraphValidatorTest — Fase 5a: início obrigatório/único, nó órfão, aresta para nó
 * inexistente, tipo desconhecido, canal incompatível, bloqueio de publicação por nó não
 * implementado (nenhum está, nesta sub-fase), ciclo é só aviso.
 */
class FlowGraphValidatorTest {

    private final FlowGraphValidator validator = new FlowGraphValidator(new ObjectMapper(), new FlowGraphNodeCatalog());

    private static String graph(String nodesJson, String edgesJson) {
        return "{\"schemaVersion\":1,\"nodes\":" + nodesJson + ",\"edges\":" + edgesJson + "}";
    }

    @Test
    @DisplayName("grafo vazio é rejeitado")
    void emptyGraph_rejected() {
        var result = validator.validate(graph("[]", "[]"), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("vazio"));
    }

    @Test
    @DisplayName("JSON inválido é rejeitado")
    void invalidJson_rejected() {
        var result = validator.validate("{not json", "voice", false);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    @DisplayName("grafo sem nó de início é rejeitado")
    void missingStartNode_rejected() {
        var nodes = "[{\"id\":\"n1\",\"type\":\"encerrar\"}]";
        var result = validator.validate(graph(nodes, "[]"), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("início"));
    }

    @Test
    @DisplayName("grafo com dois nós de início é rejeitado")
    void twoStartNodes_rejected() {
        var nodes = "[{\"id\":\"n1\",\"type\":\"inicio\"},{\"id\":\"n2\",\"type\":\"inicio\"}]";
        var result = validator.validate(graph(nodes, "[]"), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("mais de um nó de início"));
    }

    @Test
    @DisplayName("nó órfão (sem aresta de entrada) é rejeitado")
    void orphanNode_rejected() {
        var nodes = "[{\"id\":\"n1\",\"type\":\"inicio\"},{\"id\":\"n2\",\"type\":\"encerrar\"}]";
        var result = validator.validate(graph(nodes, "[]"), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> "n2".equals(e.nodeId()) && e.message().contains("órfão"));
    }

    @Test
    @DisplayName("aresta apontando para nó inexistente é rejeitada")
    void edgeToUnknownNode_rejected() {
        var nodes = "[{\"id\":\"n1\",\"type\":\"inicio\"}]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n99\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("nó inexistente"));
    }

    @Test
    @DisplayName("tipo de nó desconhecido é rejeitado")
    void unknownNodeType_rejected() {
        var nodes = "[{\"id\":\"n1\",\"type\":\"inicio\"},{\"id\":\"n2\",\"type\":\"nao_existe\"}]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> "n2".equals(e.nodeId()) && e.message().contains("desconhecido"));
    }

    @Test
    @DisplayName("nó exclusivo de voz num fluxo de chat é rejeitado")
    void voiceOnlyNodeInChatFlow_rejected() {
        var nodes = "[{\"id\":\"n1\",\"type\":\"inicio\"},{\"id\":\"n2\",\"type\":\"transferir_ramal\"}]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "chat", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("incompatível"));
    }

    @Test
    @DisplayName("grafo válido para salvar rascunho não gera erro mesmo com nó ainda não implementado")
    void validGraph_savingDraft_noErrorForUnimplementedNode() {
        var nodes = "[{\"id\":\"n1\",\"type\":\"inicio\"},{\"id\":\"n2\",\"type\":\"encerrar\"}]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("publicar bloqueia fluxo que usa nó ainda não implementado pelo motor")
    void publish_nodeNotImplemented_rejected() {
        var nodes = "[{\"id\":\"n1\",\"type\":\"inicio\"},{\"id\":\"n2\",\"type\":\"encerrar\"}]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", true);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("não é executado pelo motor"));
    }

    @Test
    @DisplayName("ciclo simples gera só aviso, não bloqueia")
    void cycle_isWarningOnly() {
        var nodes =
                "[{\"id\":\"n1\",\"type\":\"inicio\"},{\"id\":\"n2\",\"type\":\"condicao\"},"
                        + "{\"id\":\"n3\",\"type\":\"condicao\"}]";
        var edges =
                "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                        + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\"},"
                        + "{\"id\":\"e3\",\"source\":\"n3\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.message().contains("ciclo"));
    }
}
