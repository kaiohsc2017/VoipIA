package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * MenuNodeHandler — nó "menu_opcoes" (Fase 5b). A propriedade {@code opcoes} descreve o
 * mapeamento dígito→id-da-aresta-de-saída no formato {@code "1=edgeId1;2=edgeId2"} (separador
 * {@code ;} entre pares, {@code =} entre dígito e id da aresta — espaços em branco são ignorados).
 * Sem resposta válida no timeout, ou desistência (hangup), a execução termina sem seguir aresta.
 */
@Component
public class MenuNodeHandler implements NodeHandler {

    private static final Duration PROMPT_TIMEOUT = Duration.ofSeconds(10);

    @Override
    public String nodeType() {
        return "menu_opcoes";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var mapping = parseOpcoes(node.data().property("opcoes"));
        if (mapping.isEmpty()) {
            return graph.outgoingEdges(node.id()).stream().findFirst();
        }
        var result = context.driver().promptChoice(List.copyOf(mapping.keySet()), PROMPT_TIMEOUT);
        if (result.outcome() != ChannelDriver.PromptResult.Outcome.CHOSEN) {
            // Timeout/desistência sem dígito válido — nunca deixa o canal preso sem hangup dentro
            // do Stasis (catálogo v1 não tem "aresta de timeout" própria; encerrar é o seguro).
            if (result.outcome() == ChannelDriver.PromptResult.Outcome.TIMEOUT) {
                context.driver().end();
            }
            return Optional.empty();
        }
        var edgeId = mapping.get(result.choice());
        return graph.outgoingEdges(node.id()).stream().filter(e -> e.id().equals(edgeId)).findFirst();
    }

    private Map<String, String> parseOpcoes(String opcoes) {
        if (opcoes == null || opcoes.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(opcoes.split(";"))
                .map(String::trim)
                .filter(part -> part.contains("="))
                .collect(
                        Collectors.toMap(
                                part -> part.substring(0, part.indexOf('=')).trim(),
                                part -> part.substring(part.indexOf('=') + 1).trim(),
                                (a, b) -> a,
                                java.util.LinkedHashMap::new));
    }
}
