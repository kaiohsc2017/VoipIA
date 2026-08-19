package com.asteriskia.domain.callcenter.wfm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueMember;
import com.asteriskia.domain.callcenter.CcQueueMemberRepository;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueuePredictiveWfmServiceTest {

    @Mock
    private CcQueueRepository queueRepository;

    @Mock
    private CcQueueMemberRepository queueMemberRepository;

    @Mock
    private CcQueueWfmForecastRepository forecastRepository;

    private ErlangCCalculator erlangCCalculator;
    private QueuePredictiveWfmService wfmService;

    @BeforeEach
    void setUp() {
        erlangCCalculator = new ErlangCCalculator();
        wfmService = new QueuePredictiveWfmService(queueRepository, queueMemberRepository, forecastRepository, erlangCCalculator);
    }

    @Test
    void testGenerateForecastForQueue() {
        CcQueue queue = CcQueue.builder()
                .id(1L)
                .name("Fila N1 - Suporte")
                .timeoutSeconds(180)
                .build();

        when(queueRepository.findById(1L)).thenReturn(Optional.of(queue));
        when(queueMemberRepository.findByQueueId(1L)).thenReturn(List.of(new CcQueueMember(), new CcQueueMember(), new CcQueueMember()));
        when(forecastRepository.save(any(CcQueueWfmForecast.class))).thenAnswer(i -> {
            CcQueueWfmForecast f = i.getArgument(0);
            f.setId(100L);
            return f;
        });

        var forecasts = wfmService.generateForecastForQueue(1L, 60);

        assertNotNull(forecasts);
        assertEquals(4, forecasts.size(), "Deve gerar 4 intervalos de 15 min para horizonte de 60 min");
        assertEquals("Fila N1 - Suporte", forecasts.get(0).queueName());
        assertTrue(forecasts.get(0).predictedCallVolume() > 0);
        assertTrue(forecasts.get(0).requiredAgents() > 0);
    }

    @Test
    void testQueueNotFound() {
        when(queueRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> wfmService.generateForecastForQueue(999L, 60));
    }
}
