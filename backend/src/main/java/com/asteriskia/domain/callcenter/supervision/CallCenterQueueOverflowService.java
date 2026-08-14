package com.asteriskia.domain.callcenter.supervision;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.integration.ami.AmiOriginateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterQueueOverflowService — avalia o transbordo (overflow) automático de fila (Fase
 * 5e.2): quando o tempo de espera de um chamador excede {@code overflow_after_seconds}, ou o
 * tamanho da fila de espera excede {@code overflow_max_waiting}, o chamador é redirecionado via
 * AMI para a fila configurada em {@code overflow_queue_id}.
 *
 * <p>Reusa integralmente a infraestrutura já existente do painel de supervisão (Fase 15) — nunca
 * duplica lógica de consulta/roteamento: {@link AmiQueueStatusClient} (mesma consulta ao vivo
 * {@code Action: QueueStatus} usada pelo painel) e {@link AmiOriginateService#redirectChannel}
 * (mesma ação AMI {@code Redirect} já usada por {@code CallCenterSupervisionActionService}, que
 * já sanitiza os campos AMI). Decisão de arquitetura: transbordo é avaliado por consulta
 * periódica ao AMI (mesmo padrão de {@code CallCenterSlaAlertService}), não por alteração do
 * dialplan {@code Queue()} compartilhado por todas as filas — reduz o risco desta fatia (nenhuma
 * mudança em {@code extensions.conf.template}) ao custo de granularidade limitada ao intervalo do
 * scheduler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterQueueOverflowService {

    // Mesmo contexto fixo já usado por CallCenterSupervisionActionService.redirectToQueue/Agent —
    // as duas extensões _5XXX do dialplan (ramais-internos e ramais-webrtc) resolvem para o mesmo
    // destino, então reaproveitar "ramais-internos" aqui não introduz um caminho novo de risco.
    private static final String VOICE_CONTEXT = "ramais-internos";
    private static final int VOICE_PRIORITY = 1;

    private final CcQueueRepository queueRepository;
    private final AmiQueueStatusClient amiQueueStatusClient;
    private final AmiOriginateService amiOriginateService;

    @Transactional(readOnly = true)
    public void checkAndOverflow() {
        for (var queue : queueRepository.findByActiveTrueAndOverflowQueueIsNotNull()) {
            processQueue(queue);
        }
    }

    private void processQueue(CcQueue queue) {
        var overflowQueue = queue.getOverflowQueue();
        if (overflowQueue == null || !Boolean.TRUE.equals(overflowQueue.getActive())) {
            // Fila de destino removida/desativada desde a última leitura — nada a fazer até o
            // cadastro ser corrigido (a config em si não é apagada, só ignorada).
            return;
        }
        var afterSeconds = queue.getOverflowAfterSeconds();
        var maxWaiting = queue.getOverflowMaxWaiting();
        if (afterSeconds == null && maxWaiting == null) {
            return;
        }

        var waiting = amiQueueStatusClient.queueStatus(queue.getName());
        for (var caller : waiting) {
            if (!shouldOverflow(caller, afterSeconds, maxWaiting)) {
                continue;
            }
            if (caller.channelName() == null) {
                log.warn(
                        "Transbordo elegível sem nome de canal resolvido — ignorado (fila={} ani={}).",
                        queue.getName(),
                        caller.ani());
                continue;
            }
            var ok =
                    amiOriginateService.redirectChannel(
                            caller.channelName(), VOICE_CONTEXT, overflowQueue.getName(), VOICE_PRIORITY);
            if (ok) {
                log.info(
                        "Transbordo aplicado: fila={} destino={} canal={} waitSeconds={} position={}",
                        queue.getName(),
                        overflowQueue.getName(),
                        caller.channelName(),
                        caller.waitSeconds(),
                        caller.position());
            } else {
                log.warn(
                        "Falha ao aplicar transbordo via AMI (Redirect): fila={} canal={}",
                        queue.getName(),
                        caller.channelName());
            }
        }
    }

    private boolean shouldOverflow(WaitingCallerView caller, Integer afterSeconds, Integer maxWaiting) {
        var byTime = afterSeconds != null && caller.waitSeconds() != null && caller.waitSeconds() >= afterSeconds;
        var byPosition = maxWaiting != null && caller.position() != null && caller.position() > maxWaiting;
        return byTime || byPosition;
    }
}
