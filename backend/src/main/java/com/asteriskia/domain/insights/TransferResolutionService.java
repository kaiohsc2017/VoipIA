package com.asteriskia.domain.insights;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * TransferResolutionService — tenta correlacionar eventos de transferência
 * (call_transfer_events) com a gravação real da perna de destino, nos DOIS
 * sentidos, porque a ordem de ingestão entre origem e destino não é
 * garantida (a gravação de destino pode chegar em /opt/audio antes ou depois
 * da de origem):
 *
 * 1. "Esta chamada é origem": os eventos de transferência desta chamada
 *    podem já encontrar a chamada de destino entre as já ingeridas.
 * 2. "Esta chamada é destino": esta chamada pode ser exatamente o destino
 *    que eventos pendentes de OUTRAS chamadas já ingeridas estavam esperando.
 *
 * Quando não resolve, o evento fica com resolved_at=null indefinidamente —
 * isso é o estado normal (confirmado empiricamente: 0/5 transferências reais
 * do lote inicial de 52 chamadas resolveram), não um erro.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferResolutionService {

    private final CallAudioFileRepository audioFileRepository;
    private final CallTransferEventRepository transferEventRepository;

    @Transactional
    public void resolveForAudioFile(CallAudioFile audioFile) {
        resolveOutgoing(audioFile);
        resolveIncoming(audioFile);
    }

    /** Esta chamada é origem — tenta resolver os próprios eventos de transferência
     * contra gravações já ingeridas. */
    private void resolveOutgoing(CallAudioFile audioFile) {
        List<CallTransferEvent> events = transferEventRepository.findByAudioFileIdOrderByTransferOrderAsc(audioFile.getId());
        for (CallTransferEvent event : events) {
            if (event.getResolvedAt() != null || event.getTargetSwitchCallId() == null) {
                continue;
            }
            audioFileRepository.findBySwitchCallId(event.getTargetSwitchCallId()).stream()
                    // Exclui a própria gravação: um switch_call_id não é garantido único
                    // (ver CallAudioFileRepository) — sem este guard, se o globalcallid do
                    // evento coincidir com o próprio switch_call_id da chamada de origem,
                    // a correlação aponta pra si mesma e "Ramal/Atendente destino" mostra
                    // erroneamente o próprio atendente de origem em vez de "Não identificado".
                    .filter(candidate -> !candidate.getId().equals(audioFile.getId()))
                    .findFirst()
                    .ifPresent(target -> apply(event, target));
        }
    }

    /** Esta chamada é destino — resolve eventos pendentes de outras chamadas que
     * apontavam pra ela. */
    private void resolveIncoming(CallAudioFile audioFile) {
        if (audioFile.getSwitchCallId() == null || audioFile.getSwitchCallId().isBlank()) {
            return;
        }
        List<CallTransferEvent> pending =
                transferEventRepository.findByTargetSwitchCallIdAndResolvedAtIsNull(audioFile.getSwitchCallId());
        for (CallTransferEvent event : pending) {
            // Mesmo guard de auto-correlação do sentido outgoing: um evento cujo
            // audioFileId já é o desta própria chamada não é uma transferência real.
            if (event.getAudioFileId().equals(audioFile.getId())) {
                continue;
            }
            apply(event, audioFile);
        }
    }

    private void apply(CallTransferEvent event, CallAudioFile target) {
        event.setTargetExtension(target.getExtension());
        event.setTargetAgentName(target.getAgentName());
        event.setTargetAudioFileId(target.getId());
        event.setResolvedAt(LocalDateTime.now());
        transferEventRepository.save(event);
        log.info("Transferência resolvida: evento id={} (chamada origem id={}) -> destino id={} (ramal={})",
                event.getId(), event.getAudioFileId(), target.getId(), target.getExtension());
    }
}
