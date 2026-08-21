package com.asteriskia.domain.callcenter.wfm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueMemberRepository;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.reports.CcAggQueueDaily;
import com.asteriskia.domain.callcenter.reports.CcAggQueueDailyRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Mock
    private CcAggQueueDailyRepository aggQueueDailyRepository;

    private ErlangCCalculator erlangCCalculator;
    private QueuePredictiveWfmService wfmService;

    @BeforeEach
    void setUp() {
        erlangCCalculator = new ErlangCCalculator();
        wfmService = new QueuePredictiveWfmService(
                queueRepository, queueMemberRepository, forecastRepository, aggQueueDailyRepository, erlangCCalculator);
    }

    @Test
    void testGenerateForecastForQueue_semHistorico_usaFallbackConservador() {
        CcQueue queue = CcQueue.builder()
                .id(1L)
                .name("Fila N1 - Suporte")
                .timeoutSeconds(180)
                .build();

        when(queueRepository.findById(1L)).thenReturn(Optional.of(queue));
        when(queueMemberRepository.countByQueueId(1L)).thenReturn(3L);
        when(aggQueueDailyRepository.findByQueueIdAndDateBetweenOrderByDateAsc(eq(1L), any(), any()))
                .thenReturn(List.of());
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
        assertEquals("ERLANG_C_FALLBACK_DADOS_INSUFICIENTES", forecasts.get(0).algorithm(),
                "Sem histórico suficiente, o algoritmo deve sinalizar claramente o fallback — nunca um dado fictício disfarçado de real");
    }

    @Test
    void testGenerateForecastForQueue_comHistoricoReal_calculaBaselineHistorico() {
        CcQueue queue = CcQueue.builder()
                .id(1L)
                .name("Fila N1 - Suporte")
                .timeoutSeconds(180)
                .build();

        when(queueRepository.findById(1L)).thenReturn(Optional.of(queue));
        when(queueMemberRepository.countByQueueId(1L)).thenReturn(3L);

        List<CcAggQueueDaily> history = List.of(
                dailyOf(queue, LocalDate.now().minusDays(4), 100),
                dailyOf(queue, LocalDate.now().minusDays(3), 120),
                dailyOf(queue, LocalDate.now().minusDays(2), 140),
                dailyOf(queue, LocalDate.now().minusDays(1), 160)
        );
        when(aggQueueDailyRepository.findByQueueIdAndDateBetweenOrderByDateAsc(eq(1L), any(), any()))
                .thenReturn(history);
        when(forecastRepository.save(any(CcQueueWfmForecast.class))).thenAnswer(i -> {
            CcQueueWfmForecast f = i.getArgument(0);
            f.setId(100L);
            return f;
        });

        var forecasts = wfmService.generateForecastForQueue(1L, 60);

        assertNotNull(forecasts);
        assertEquals(4, forecasts.size());
        assertEquals("ERLANG_C_HISTORICO_28D", forecasts.get(0).algorithm());
        assertTrue(forecasts.get(0).predictedCallVolume() > 0);
    }

    @Test
    void testQueueNotFound() {
        when(queueRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> wfmService.generateForecastForQueue(999L, 60));
    }

    private static CcAggQueueDaily dailyOf(CcQueue queue, LocalDate date, int received) {
        return CcAggQueueDaily.builder()
                .queue(queue)
                .date(date)
                .received(received)
                .answered(received)
                .abandoned(0)
                .computedAt(LocalDateTime.now())
                .build();
    }
}
