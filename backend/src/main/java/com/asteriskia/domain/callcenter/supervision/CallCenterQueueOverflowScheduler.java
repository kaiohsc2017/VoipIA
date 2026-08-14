package com.asteriskia.domain.callcenter.supervision;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterQueueOverflowScheduler — dispara a verificação periódica de transbordo (Fase 5e.2).
 * Intervalo curto (default 15s, configurável) — diferente do alerta de SLA (janela de 10 min),
 * porque o transbordo precisa reagir perto do tempo real para não deixar o cliente esperar muito
 * além do limiar configurado.
 */
@Component
@RequiredArgsConstructor
public class CallCenterQueueOverflowScheduler {

    private final CallCenterQueueOverflowService service;

    @Scheduled(fixedDelayString = "${app.callcenter.overflow-check-interval-ms:15000}")
    public void scheduledCheck() {
        service.checkAndOverflow();
    }
}
