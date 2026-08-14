package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.asteriskia.domain.callcenter.kb.CallCenterKbAnswerService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ConsultarBaseNodeHandler — nó "consultar_base" (Fase 25, §25.3), exclusivo do canal chat.
 * Recupera a pergunta de uma variável do contexto (property {@code variavelPergunta}, coletada
 * antes por um nó "coletar_texto") e consulta {@link CallCenterKbAnswerService}. Roteamento por
 * aresta, mesmo padrão de {@code ConditionNodeHandler}: primeira aresta de saída = resposta
 * encontrada; segunda = não encontrada (o designer do fluxo normalmente conecta essa segunda
 * aresta a um nó "enviar_fila"). Se não houver segunda aresta configurada, usa a property
 * {@code filaId} (fila de fallback) como rede de segurança — nunca deixa a conversa presa sem
 * escalar quando o bot não sabe responder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsultarBaseNodeHandler implements NodeHandler {

    private static final String DEFAULT_VARIABLE = "pergunta";

    private final CallCenterKbAnswerService answerService;
    private final CcQueueRepository queueRepository;

    @Override
    public String nodeType() {
        return "consultar_base";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var driver = context.driver();
        var sessionId = parseSessionId(context.channelId());
        if (sessionId == null) {
            log.warn("Nó consultar_base fora do canal chat (channelId={}) — encerrando.", context.channelId());
            driver.end();
            return Optional.empty();
        }

        var variableName = node.data().property("variavelPergunta");
        var question = context.getVariable(variableName == null || variableName.isBlank() ? DEFAULT_VARIABLE : variableName);

        var result = answerService.answer(sessionId, question);
        var outgoing = graph.outgoingEdges(node.id());

        if (result.matched()) {
            driver.playMessage(null, result.answerText());
            if (!outgoing.isEmpty()) {
                return Optional.of(outgoing.get(0));
            }
            driver.end();
            return Optional.empty();
        }

        if (outgoing.size() > 1) {
            return Optional.of(outgoing.get(1));
        }
        return escalateToFallbackQueue(node, driver);
    }

    private Optional<FlowGraph.Edge> escalateToFallbackQueue(FlowGraph.Node node, ChannelDriver driver) {
        var filaId = node.data().property("filaId");
        Optional<CcQueue> queue = Optional.empty();
        try {
            queue = filaId == null || filaId.isBlank() ? Optional.empty() : queueRepository.findById(Long.valueOf(filaId));
        } catch (NumberFormatException ignored) {
            // filaId malformado — tratado abaixo como fila não encontrada.
        }
        if (queue.isEmpty()) {
            log.warn(
                    "Nó consultar_base sem resposta e sem segunda aresta/fila de fallback configurada "
                            + "(filaId={}) — encerrando conversa.",
                    filaId);
            driver.end();
            return Optional.empty();
        }
        driver.transferToQueue(queue.get().getName());
        return Optional.empty();
    }

    private Long parseSessionId(String channelId) {
        var prefix = "chat-session-";
        if (channelId == null || !channelId.startsWith(prefix)) {
            return null;
        }
        try {
            return Long.valueOf(channelId.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
