package com.asteriskia.domain.callcenter.flow;

import java.util.List;

/**
 * FlowGraphNodeType — um tipo de nó do catálogo do Flow Builder (Fase 5). {@code implementado}
 * indica se o motor de execução já sabe rodar esse nó — nesta sub-fase (5a) nenhum está, pois o
 * motor (ARI/Stasis) só chega na Fase 5b. Um fluxo não pode ser publicado usando nó não
 * implementado ({@link FlowGraphValidator}).
 */
public record FlowGraphNodeType(
        String type, String label, String channel, boolean implementado, List<NodeProperty> properties) {

    /**
     * NodeProperty — descreve um campo do painel de propriedades do editor, de forma genérica.
     * Tipos: {@code string|number|boolean|select|audio|keypad} (os dois últimos, Fase 5c).
     * {@code select} sem {@code options} nem {@code optionsEndpoint} preenchidos continua caindo
     * como campo texto no editor (mantém o comportamento anterior).
     *
     * <p>{@code optionsEndpoint} (Fase A do nó {@code agente_ia}) é um caminho relativo à base da
     * API do Call Center (ex.: {@code "/callcenter/ia-agents"}) que o frontend consulta para
     * popular um {@code select} dinamicamente, quando a lista de opções vem de um cadastro (não
     * de uma lista fixa em código) — hoje só usado por {@code configuracaoIaId}; os demais
     * selects dinâmicos ({@code filaId}/{@code pesquisaId}/{@code calendarioId}) ficam fora do
     * escopo desta fase.
     */
    public record NodeProperty(
            String name, String label, String type, List<Option> options, boolean required, String optionsEndpoint) {

        public NodeProperty(String name, String label, String type, List<Option> options, boolean required) {
            this(name, label, type, options, required, null);
        }

        /** Compatibilidade com o catálogo anterior à Fase 5c (sem opções/obrigatoriedade). */
        public NodeProperty(String name, String label, String type) {
            this(name, label, type, List.of(), false, null);
        }

        public record Option(String value, String label) {}
    }
}
