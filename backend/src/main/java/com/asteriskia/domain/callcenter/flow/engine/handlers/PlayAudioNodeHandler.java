package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * PlayAudioNodeHandler — nó "tocar_audio" (Fase 5b). Toca o áudio da propriedade
 * {@code audioPath}; TTS de verdade (propriedade {@code texto}) fica para a sub-fase 5d, que
 * integra o ai-agent existente — aqui, se só houver texto, o driver do canal apenas registra
 * que TTS ainda não está disponível e segue, sem travar a chamada.
 */
@Component
public class PlayAudioNodeHandler implements NodeHandler {

    @Override
    public String nodeType() {
        return "tocar_audio";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var audioPath = node.data().property("audioPath");
        var texto = node.data().property("texto");
        context.driver().playMessage(audioPath, texto);
        return graph.outgoingEdges(node.id()).stream().findFirst();
    }
}
