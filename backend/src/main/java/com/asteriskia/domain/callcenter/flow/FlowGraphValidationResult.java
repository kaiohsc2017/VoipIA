package com.asteriskia.domain.callcenter.flow;

import java.util.List;

/**
 * FlowGraphValidationResult — saída de {@link FlowGraphValidator}. {@code errors} bloqueiam a
 * publicação (mas não o salvamento do rascunho); {@code warnings} são só informativos (ex.: ciclo
 * simples, sem nó de consumo real ainda — a Fase 5b terá mais contexto para decidir se é erro).
 */
public record FlowGraphValidationResult(List<Issue> errors, List<Issue> warnings) {

    public record Issue(String nodeId, String message) {}

    public boolean isValid() {
        return errors.isEmpty();
    }
}
