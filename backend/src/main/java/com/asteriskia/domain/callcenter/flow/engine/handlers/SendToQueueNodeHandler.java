package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SendToQueueNodeHandler — nó "enviar_fila" (Fase 5b). Encaminha via
 * {@code ChannelDriver.transferToQueue}, que por trás faz {@code continueInDialplan} para a
 * extensão {@code _5XXX} já existente (Fase 3) — herda de graça gravação, aviso de consentimento e
 * ingestão, nunca reimplementados aqui. A propriedade {@code filaId} guarda o id de
 * {@code cc_queues}; o nome da fila é o próprio ramal (mesma convenção do dialplan de
 * {@code _5XXX}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendToQueueNodeHandler implements NodeHandler {

    private final CcQueueRepository queueRepository;

    @Override
    public String nodeType() {
        return "enviar_fila";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var filaId = node.data().property("filaId");
        Optional<CcQueue> queue = Optional.empty();
        try {
            queue = filaId == null ? Optional.empty() : queueRepository.findById(Long.valueOf(filaId));
        } catch (NumberFormatException ignored) {
            // filaId malformado — tratado abaixo como fila não encontrada.
        }
        if (queue.isEmpty()) {
            log.warn("Nó enviar_fila sem fila válida configurada (filaId={}) — encerrando chamada.", filaId);
            context.driver().end();
            return Optional.empty();
        }
        context.driver().transferToQueue(queue.get().getName());
        return Optional.empty();
    }
}
