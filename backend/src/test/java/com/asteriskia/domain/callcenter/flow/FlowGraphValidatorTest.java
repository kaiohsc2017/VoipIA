package com.asteriskia.domain.callcenter.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FlowGraphValidatorTest — início obrigatório/único, nó órfão, aresta para nó inexistente, tipo
 * desconhecido, canal incompatível, bloqueio de publicação por nó não implementado, ciclo é só
 * aviso. Desde a sub-fase 5b, 7 tipos passaram a {@code implementado=true}
 * ({@code FlowGraphNodeCatalog}) — os testes de bloqueio usam {@code consultar_api}, que
 * permanece não implementado (fica para a 5d).
 *
 * <p>Os nós usam o formato real persistido pela UI: {@code type} é sempre {@code "generic"} (tipo
 * de renderização do React Flow) — o tipo de domínio do catálogo vem em {@code data.nodeType}
 * (ver {@link #n}). Ler o {@code type} de fora seria o mesmo bug encontrado e corrigido na
 * sub-fase 5b: todo nó real virava "tipo desconhecido".
 */
class FlowGraphValidatorTest {

    private final FlowGraphValidator validator = new FlowGraphValidator(new ObjectMapper(), new FlowGraphNodeCatalog());

    private static String graph(String nodesJson, String edgesJson) {
        return "{\"schemaVersion\":1,\"nodes\":" + nodesJson + ",\"edges\":" + edgesJson + "}";
    }

    private static String n(String id, String nodeType) {
        return "{\"id\":\"" + id + "\",\"type\":\"generic\",\"data\":{\"nodeType\":\"" + nodeType + "\"}}";
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
        var nodes = "[" + n("n1", "encerrar") + "]";
        var result = validator.validate(graph(nodes, "[]"), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("início"));
    }

    @Test
    @DisplayName("grafo com dois nós de início é rejeitado")
    void twoStartNodes_rejected() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "inicio") + "]";
        var result = validator.validate(graph(nodes, "[]"), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("mais de um nó de início"));
    }

    @Test
    @DisplayName("nó órfão (sem aresta de entrada) é rejeitado")
    void orphanNode_rejected() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "encerrar") + "]";
        var result = validator.validate(graph(nodes, "[]"), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> "n2".equals(e.nodeId()) && e.message().contains("órfão"));
    }

    @Test
    @DisplayName("aresta apontando para nó inexistente é rejeitada")
    void edgeToUnknownNode_rejected() {
        var nodes = "[" + n("n1", "inicio") + "]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n99\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("nó inexistente"));
    }

    @Test
    @DisplayName("tipo de nó desconhecido é rejeitado")
    void unknownNodeType_rejected() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "nao_existe") + "]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> "n2".equals(e.nodeId()) && e.message().contains("desconhecido"));
    }

    @Test
    @DisplayName("nó exclusivo de voz num fluxo de chat é rejeitado")
    void voiceOnlyNodeInChatFlow_rejected() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "transferir_ramal") + "]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "chat", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("incompatível"));
    }

    @Test
    @DisplayName("grafo válido para salvar rascunho não gera erro mesmo com nó ainda não implementado")
    void validGraph_savingDraft_noErrorForUnimplementedNode() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "consultar_api") + "]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("publicar bloqueia fluxo que usa nó ainda não implementado pelo motor")
    void publish_nodeNotImplemented_rejected() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "consultar_api") + "]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", true);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("não é executado pelo motor"));
    }

    @Test
    @DisplayName("publicar permite fluxo que usa só os 7 tipos de nó implementados pelo motor (Fase 5b)")
    void publish_onlyImplementedNodes_allowed() {
        var nodes =
                "["
                        + n("n1", "inicio") + ","
                        + n("n2", "tocar_audio") + ","
                        + n("n3", "menu_opcoes") + ","
                        + n("n4", "condicao") + ","
                        + n("n5", "definir_variavel") + ","
                        + n("n6", "enviar_fila") + ","
                        + n("n7", "encerrar")
                        + "]";
        var edges =
                "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                        + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\"},"
                        + "{\"id\":\"e3\",\"source\":\"n3\",\"target\":\"n4\"},"
                        + "{\"id\":\"e4\",\"source\":\"n4\",\"target\":\"n5\"},"
                        + "{\"id\":\"e5\",\"source\":\"n4\",\"target\":\"n6\"},"
                        + "{\"id\":\"e6\",\"source\":\"n5\",\"target\":\"n7\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", true);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("ciclo simples gera só aviso, não bloqueia")
    void cycle_isWarningOnly() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "condicao") + "," + n("n3", "condicao") + "]";
        var edges =
                "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                        + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\"},"
                        + "{\"id\":\"e3\",\"source\":\"n3\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.message().contains("ciclo"));
    }
}
