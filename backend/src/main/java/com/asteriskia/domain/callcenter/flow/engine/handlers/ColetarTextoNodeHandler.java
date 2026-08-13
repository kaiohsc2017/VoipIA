package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * ColetarTextoNodeHandler — nó "coletar_texto" (Fase 24, exclusivo do canal chat — equivalente
 * chat de "coletar_entrada", que continua exclusivo de voz e não implementado). Sem timeout
 * configurado, aguarda 60s — mais longo que o timeout de menu de voz (10s) porque digitar leva
 * mais tempo que apertar uma tecla.
 */
@Component
public class ColetarTextoNodeHandler implements NodeHandler {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    @Override
    public String nodeType() {
        return "coletar_texto";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var driver = context.driver();
        var result = driver.collectText(parseTimeout(node.data().property("timeoutSegundos")));
        if (result.outcome() == ChannelDriver.TextResult.Outcome.HUNG_UP) {
            return Optional.empty();
        }
        if (result.outcome() == ChannelDriver.TextResult.Outcome.COLLECTED) {
            var variavel = node.data().property("variavel");
            if (variavel != null && !variavel.isBlank()) {
                context.setVariable(variavel, result.text());
                driver.setVariable(variavel, result.text());
            }
        }
        // Sem aresta de saída (seja TIMEOUT ou COLLECTED), mesmo comportamento seguro do menu
        // (MenuNodeHandler.followOrEnd): encerra em vez de deixar a sessão presa em status="bot"
        // para sempre — sem isso, um fluxo terminando em coletar_texto nunca chamava
        // driver.end()/closeByBot, e nenhum agente conseguia assumir a conversa (claim só aceita
        // status="waiting").
        var edge = graph.outgoingEdges(node.id()).stream().findFirst();
        if (edge.isEmpty()) {
            driver.end();
        }
        return edge;
    }

    private Duration parseTimeout(String raw) {
        try {
            return raw == null || raw.isBlank() ? DEFAULT_TIMEOUT : Duration.ofSeconds(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT;
        }
    }
}
