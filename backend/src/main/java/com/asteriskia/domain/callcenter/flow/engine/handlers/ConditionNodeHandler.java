package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * ConditionNodeHandler — nó "condicao" (Fase 5b). A propriedade {@code expressao} é avaliada de
 * forma simples e segura: só comparação de igualdade/desigualdade entre uma variável do contexto
 * e um literal ({@code variavel==valor} ou {@code variavel!=valor}) — nunca um {@code eval} de
 * expressão arbitrária, já que é entrada de usuário com permissão de escrita no fluxo, não pode
 * virar execução de código. Ramificação: primeira aresta de saída (ordenada por id, ver
 * {@link FlowGraph}) = ramo verdadeiro; segunda = ramo falso. Expressão ausente/malformada é
 * tratada como falsa.
 */
@Component
public class ConditionNodeHandler implements NodeHandler {

    private static final Pattern EXPRESSION =
            Pattern.compile("^\\s*(\\w+)\\s*(==|!=)\\s*(.*)\\s*$");

    @Override
    public String nodeType() {
        return "condicao";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var outgoing = graph.outgoingEdges(node.id());
        var result = evaluate(node.data().property("expressao"), context);
        if (result && !outgoing.isEmpty()) {
            return Optional.of(outgoing.get(0));
        }
        if (!result && outgoing.size() > 1) {
            return Optional.of(outgoing.get(1));
        }
        return Optional.empty();
    }

    private boolean evaluate(String expressao, FlowExecutionContext context) {
        if (expressao == null) {
            return false;
        }
        var matcher = EXPRESSION.matcher(expressao);
        if (!matcher.matches()) {
            return false;
        }
        var variable = context.getVariable(matcher.group(1));
        var operator = matcher.group(2);
        var literal = matcher.group(3).trim();
        var equal = Objects.equals(variable, literal);
        return "==".equals(operator) ? equal : !equal;
    }
}
