package com.asteriskia.domain.callcenter.supervision;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.integration.ami.AmiOriginateService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterQueueOverflowServiceTest — cobre os dois critérios de transbordo (Fase 5e.2): tempo de
 * espera excedido e tamanho da fila excedido, mais os casos de borda (fila de destino
 * inativa/removida, sem nome de canal resolvido, falha do AMI Redirect nunca lança).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterQueueOverflowServiceTest {

    @Mock private CcQueueRepository queueRepository;
    @Mock private AmiQueueStatusClient amiQueueStatusClient;
    @Mock private AmiOriginateService amiOriginateService;

    private CallCenterQueueOverflowService service() {
        return new CallCenterQueueOverflowService(queueRepository, amiQueueStatusClient, amiOriginateService);
    }

    @Test
    @DisplayName("chamador com espera >= overflowAfterSeconds é redirecionado para a fila de transbordo")
    void checkAndOverflow_byTime_redirects() {
        var overflowQueue = CcQueue.builder().id(2L).name("5002").active(true).build();
        var queue =
                CcQueue.builder().id(1L).name("5001").active(true).overflowQueue(overflowQueue).overflowAfterSeconds(30).build();
        when(queueRepository.findByActiveTrueAndOverflowQueueIsNotNull()).thenReturn(List.of(queue));
        when(amiQueueStatusClient.queueStatus("5001"))
                .thenReturn(List.of(new WaitingCallerView(1, "1199999999", 35L, "u1", "PJSIP/tronco-000001")));
        when(amiOriginateService.redirectChannel("PJSIP/tronco-000001", "ramais-internos", "5002", 1)).thenReturn(true);

        service().checkAndOverflow();

        verify(amiOriginateService).redirectChannel("PJSIP/tronco-000001", "ramais-internos", "5002", 1);
    }

    @Test
    @DisplayName("chamador com espera abaixo do limiar de tempo não é redirecionado")
    void checkAndOverflow_belowTimeThreshold_doesNotRedirect() {
        var overflowQueue = CcQueue.builder().id(2L).name("5002").active(true).build();
        var queue =
                CcQueue.builder().id(1L).name("5001").active(true).overflowQueue(overflowQueue).overflowAfterSeconds(30).build();
        when(queueRepository.findByActiveTrueAndOverflowQueueIsNotNull()).thenReturn(List.of(queue));
        when(amiQueueStatusClient.queueStatus("5001"))
                .thenReturn(List.of(new WaitingCallerView(1, "1199999999", 10L, "u1", "PJSIP/tronco-000001")));

        service().checkAndOverflow();

        verify(amiOriginateService, never()).redirectChannel(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("chamador em posição além do tamanho máximo de espera é redirecionado, mesmo dentro do tempo")
    void checkAndOverflow_byPosition_redirects() {
        var overflowQueue = CcQueue.builder().id(2L).name("5002").active(true).build();
        var queue =
                CcQueue.builder().id(1L).name("5001").active(true).overflowQueue(overflowQueue).overflowMaxWaiting(2).build();
        when(queueRepository.findByActiveTrueAndOverflowQueueIsNotNull()).thenReturn(List.of(queue));
        when(amiQueueStatusClient.queueStatus("5001"))
                .thenReturn(List.of(new WaitingCallerView(3, "1199999999", 5L, "u1", "PJSIP/tronco-000001")));
        when(amiOriginateService.redirectChannel("PJSIP/tronco-000001", "ramais-internos", "5002", 1)).thenReturn(true);

        service().checkAndOverflow();

        verify(amiOriginateService).redirectChannel("PJSIP/tronco-000001", "ramais-internos", "5002", 1);
    }

    @Test
    @DisplayName("fila de transbordo inativa/removida é ignorada — nenhum redirect")
    void checkAndOverflow_overflowQueueInactive_doesNothing() {
        var overflowQueue = CcQueue.builder().id(2L).name("5002").active(false).build();
        var queue =
                CcQueue.builder().id(1L).name("5001").active(true).overflowQueue(overflowQueue).overflowAfterSeconds(1).build();
        when(queueRepository.findByActiveTrueAndOverflowQueueIsNotNull()).thenReturn(List.of(queue));

        service().checkAndOverflow();

        verify(amiQueueStatusClient, never()).queueStatus(anyString());
        verify(amiOriginateService, never()).redirectChannel(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("chamador elegível sem nome de canal resolvido é ignorado, sem lançar exceção")
    void checkAndOverflow_missingChannelName_skipsWithoutThrowing() {
        var overflowQueue = CcQueue.builder().id(2L).name("5002").active(true).build();
        var queue =
                CcQueue.builder().id(1L).name("5001").active(true).overflowQueue(overflowQueue).overflowAfterSeconds(10).build();
        when(queueRepository.findByActiveTrueAndOverflowQueueIsNotNull()).thenReturn(List.of(queue));
        when(amiQueueStatusClient.queueStatus("5001"))
                .thenReturn(List.of(new WaitingCallerView(1, "1199999999", 50L, "u1", null)));

        service().checkAndOverflow();

        verify(amiOriginateService, never()).redirectChannel(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("falha do AMI Redirect é logada, nunca lançada")
    void checkAndOverflow_redirectFails_neverThrows() {
        var overflowQueue = CcQueue.builder().id(2L).name("5002").active(true).build();
        var queue =
                CcQueue.builder().id(1L).name("5001").active(true).overflowQueue(overflowQueue).overflowAfterSeconds(10).build();
        when(queueRepository.findByActiveTrueAndOverflowQueueIsNotNull()).thenReturn(List.of(queue));
        when(amiQueueStatusClient.queueStatus("5001"))
                .thenReturn(List.of(new WaitingCallerView(1, "1199999999", 50L, "u1", "PJSIP/tronco-000001")));
        when(amiOriginateService.redirectChannel("PJSIP/tronco-000001", "ramais-internos", "5002", 1)).thenReturn(false);

        service().checkAndOverflow();

        verify(amiOriginateService).redirectChannel("PJSIP/tronco-000001", "ramais-internos", "5002", 1);
    }

    @Test
    @DisplayName("fila sem nenhum limiar configurado nunca é consultada (defensivo)")
    void checkAndOverflow_noThresholdConfigured_neverQueriesAmi() {
        var overflowQueue = CcQueue.builder().id(2L).name("5002").active(true).build();
        var queue = CcQueue.builder().id(1L).name("5001").active(true).overflowQueue(overflowQueue).build();
        when(queueRepository.findByActiveTrueAndOverflowQueueIsNotNull()).thenReturn(List.of(queue));

        service().checkAndOverflow();

        verify(amiQueueStatusClient, never()).queueStatus(anyString());
    }
}
