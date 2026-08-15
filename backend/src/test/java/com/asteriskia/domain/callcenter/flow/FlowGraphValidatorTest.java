package com.asteriskia.domain.callcenter.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FlowGraphValidatorTest — início obrigatório/único, nó órfão, aresta para nó inexistente, tipo
 * desconhecido, canal incompatível, bloqueio de publicação por nó não implementado, ciclo é só
 * aviso. Desde a Fase 10 (nó {@code consultar_api}), **todos** os tipos do catálogo real são
 * {@code implementado=true} — os testes de bloqueio usam {@code FAKE_UNIMPLEMENTED_TYPE}, um tipo
 * sintético só desta suíte (via subclasse anônima de {@link FlowGraphNodeCatalog}), para continuar
 * cobrindo a regra em si (que segue valendo para qualquer nó novo adicionado no futuro) sem
 * depender de um exemplo real ainda pendente no catálogo de produção.
 *
 * <p>Os nós usam o formato real persistido pela UI: {@code type} é sempre {@code "generic"} (tipo
 * de renderização do React Flow) — o tipo de domínio do catálogo vem em {@code data.nodeType}
 * (ver {@link #n}). Ler o {@code type} de fora seria o mesmo bug encontrado e corrigido na
 * sub-fase 5b: todo nó real virava "tipo desconhecido".
 */
class FlowGraphValidatorTest {

    private static final String FAKE_UNIMPLEMENTED_TYPE = "fake_unimplemented_type";

    private final FlowGraphNodeCatalog catalogWithFakeUnimplementedType =
            new FlowGraphNodeCatalog() {
                @Override
                public java.util.Optional<FlowGraphNodeType> findByType(String type) {
                    if (FAKE_UNIMPLEMENTED_TYPE.equals(type)) {
                        return java.util.Optional.of(
                                new FlowGraphNodeType(FAKE_UNIMPLEMENTED_TYPE, "Tipo fake (teste)", "both", false, List.of()));
                    }
                    return super.findByType(type);
                }
            };

    private final FlowGraphValidator validator =
            new FlowGraphValidator(new ObjectMapper(), catalogWithFakeUnimplementedType);

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
    @DisplayName("Fase 24: nó exclusivo de chat (coletar_texto) num fluxo de voz é rejeitado")
    void chatOnlyNodeInVoiceFlow_rejected() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "coletar_texto") + "]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("incompatível"));
    }

    @Test
    @DisplayName("grafo válido para salvar rascunho não gera erro mesmo com nó ainda não implementado")
    void validGraph_savingDraft_noErrorForUnimplementedNode() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "fake_unimplemented_type") + "]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("publicar bloqueia fluxo que usa nó ainda não implementado pelo motor")
    void publish_nodeNotImplemented_rejected() {
        var nodes = "[" + n("n1", "inicio") + "," + n("n2", "fake_unimplemented_type") + "]";
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

    private static String menuNodeV2(String id, String opcoesMenuJson) {
        var escaped = opcoesMenuJson.replace("\"", "\\\"");
        return "{\"id\":\"" + id + "\",\"type\":\"generic\",\"data\":{\"nodeType\":\"menu_opcoes\","
                + "\"properties\":{\"opcoesMenu\":\"" + escaped + "\"}}}";
    }

    @Test
    @DisplayName("Fase 5c: menu v2 sem nenhuma opção configurada é rejeitado")
    void menuV2_noOptions_rejected() {
        var nodes = "[" + n("n1", "inicio") + "," + menuNodeV2("n2", "[]") + "]";
        var edges = "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> "n2".equals(e.nodeId()) && e.message().contains("nenhuma opção"));
    }

    @Test
    @DisplayName("Fase 5c: opção do menu v2 sem aresta correspondente é rejeitada")
    void menuV2_optionWithoutEdge_rejected() {
        var nodes =
                "["
                        + n("n1", "inicio") + ","
                        + menuNodeV2("n2", "[{\"digito\":\"1\",\"rotulo\":\"Vendas\"},{\"digito\":\"2\",\"rotulo\":\"Suporte\"}]") + ","
                        + n("n3", "encerrar")
                        + "]";
        var edges =
                "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                        + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\",\"sourceHandle\":\"opt-1\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> "n2".equals(e.nodeId()) && e.message().contains("\"2\""));
    }

    @Test
    @DisplayName("Fase 5c: aresta com handle inexistente no menu v2 é rejeitada")
    void menuV2_edgeWithUnknownHandle_rejected() {
        var nodes =
                "["
                        + n("n1", "inicio") + ","
                        + menuNodeV2("n2", "[{\"digito\":\"1\",\"rotulo\":\"Vendas\"}]") + ","
                        + n("n3", "encerrar")
                        + "]";
        var edges =
                "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                        + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\",\"sourceHandle\":\"opt-1\"},"
                        + "{\"id\":\"e3\",\"source\":\"n2\",\"target\":\"n3\",\"sourceHandle\":\"opt-9\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> "n2".equals(e.nodeId()) && e.message().contains("opção inexistente"));
    }

    @Test
    @DisplayName("Fase 5c: menu v2 completo (todas as opções + opt-timeout/opt-invalido) é válido")
    void menuV2_complete_valid() {
        var nodes =
                "["
                        + n("n1", "inicio") + ","
                        + menuNodeV2("n2", "[{\"digito\":\"1\",\"rotulo\":\"Vendas\"}]") + ","
                        + n("n3", "encerrar")
                        + "]";
        var edges =
                "[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                        + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\",\"sourceHandle\":\"opt-1\"},"
                        + "{\"id\":\"e3\",\"source\":\"n2\",\"target\":\"n3\",\"sourceHandle\":\"opt-timeout\"},"
                        + "{\"id\":\"e4\",\"source\":\"n2\",\"target\":\"n3\",\"sourceHandle\":\"opt-invalido\"}]";
        var result = validator.validate(graph(nodes, edges), "voice", false);

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
