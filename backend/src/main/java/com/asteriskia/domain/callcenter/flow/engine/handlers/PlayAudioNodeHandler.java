package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.audio.CallCenterAudioService;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PlayAudioNodeHandler — nó "tocar_audio" (Fase 5b). {@code audioPath} guarda o {@code id} da
 * biblioteca de áudios (Fase 5c, {@code CcAudioFile}) — resolvido aqui para o caminho
 * {@code sound:} que o ARI entende. TTS de verdade (propriedade {@code texto}) fica para a
 * sub-fase 5d, que integra o ai-agent existente — aqui, se só houver texto, o driver do canal
 * apenas registra que TTS ainda não está disponível e segue, sem travar a chamada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlayAudioNodeHandler implements NodeHandler {

    private final CallCenterAudioService audioService;

    @Override
    public String nodeType() {
        return "tocar_audio";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var audioPath = resolveAudioPath(node.data().property("audioPath"));
        var texto = node.data().property("texto");
        context.driver().playMessage(audioPath, texto);
        return graph.outgoingEdges(node.id()).stream().findFirst();
    }

    /** Nulo se o id não existir mais na biblioteca (áudio excluído após o fluxo publicado) — nunca
     * trava a chamada por isso, só não toca nada. */
    private String resolveAudioPath(String audioFileIdRaw) {
        if (audioFileIdRaw == null || audioFileIdRaw.isBlank()) {
            return null;
        }
        try {
            return audioService.resolveSoundPath(Long.parseLong(audioFileIdRaw.trim())).orElse(null);
        } catch (NumberFormatException e) {
            log.warn("Propriedade audioPath do nó tocar_audio não é um id numérico válido: {}", audioFileIdRaw);
            return null;
        }
    }
}
