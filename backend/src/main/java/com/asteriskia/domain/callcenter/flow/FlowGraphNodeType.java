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

    /** NodeProperty — descreve um campo do painel de propriedades do editor, de forma genérica. */
    public record NodeProperty(String name, String label, String type) {}
}
