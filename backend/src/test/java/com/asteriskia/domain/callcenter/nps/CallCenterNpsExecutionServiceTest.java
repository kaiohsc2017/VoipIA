package com.asteriskia.domain.callcenter.nps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.CcSettingsService;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CallCenterNpsExecutionServiceTest — disparo direto da pesquisa pós-fila (Fase 21, §21.2).
 * Nunca bloqueante: todo caminho termina em driver.end(), mesmo em erro. */
@ExtendWith(MockitoExtension.class)
class CallCenterNpsExecutionServiceTest {

    @Mock private CcQueueRepository queueRepository;
    @Mock private CcSettingsService settingsService;
    @Mock private CallCenterSurveyRunner runner;
    @Mock private ChannelDriver driver;

    private CallCenterNpsExecutionService newService() {
        return new CallCenterNpsExecutionService(queueRepository, settingsService, runner);
    }

    @Test
    @DisplayName("interruptor global desligado — nunca roda a pesquisa, só encerra a chamada")
    void start_globalDisabled_neverRuns() {
        when(settingsService.isNpsEnabledGlobally()).thenReturn(false);

        newService().start("chan-1", "5001", driver);

        verify(runner, never()).run(any(), any(), any(), any());
        verify(driver).end();
    }

    @Test
    @DisplayName("fila sem pesquisa configurada — não roda, só encerra")
    void start_queueWithoutSurvey_neverRuns() {
        when(settingsService.isNpsEnabledGlobally()).thenReturn(true);
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(CcQueue.builder().name("5001").build()));

        newService().start("chan-1", "5001", driver);

        verify(runner, never()).run(any(), any(), any(), any());
        verify(driver).end();
    }

    @Test
    @DisplayName("pesquisa inativa na fila — não roda, só encerra")
    void start_inactiveSurvey_neverRuns() {
        var survey = CcSurvey.builder().id(1L).active(false).build();
        when(settingsService.isNpsEnabledGlobally()).thenReturn(true);
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(CcQueue.builder().name("5001").survey(survey).build()));

        newService().start("chan-1", "5001", driver);

        verify(runner, never()).run(any(), any(), any(), any());
        verify(driver).end();
    }

    @Test
    @DisplayName("fila com pesquisa ativa e interruptor global ligado — roda a pesquisa e encerra depois")
    void start_activeSurveyAndGlobalEnabled_runsThenEnds() {
        var survey = CcSurvey.builder().id(1L).active(true).build();
        var queue = CcQueue.builder().name("5001").survey(survey).build();
        when(settingsService.isNpsEnabledGlobally()).thenReturn(true);
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(queue));

        newService().start("chan-1", "5001", driver);

        verify(runner).run(survey, driver, "chan-1", queue);
        verify(driver).end();
    }

    @Test
    @DisplayName("falha durante a execução da pesquisa nunca deixa de encerrar a chamada (nunca bloqueante, §4.2)")
    void start_runnerThrows_stillEndsCall() {
        var survey = CcSurvey.builder().id(1L).active(true).build();
        var queue = CcQueue.builder().name("5001").survey(survey).build();
        when(settingsService.isNpsEnabledGlobally()).thenReturn(true);
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(queue));
        org.mockito.Mockito.doThrow(new RuntimeException("falha simulada")).when(runner).run(any(), any(), any(), any());

        newService().start("chan-1", "5001", driver);

        verify(driver, times(1)).end();
    }

    @Test
    @DisplayName("fila inexistente — não roda, só encerra")
    void start_unknownQueue_neverRuns() {
        when(settingsService.isNpsEnabledGlobally()).thenReturn(true);
        when(queueRepository.findByName("5001")).thenReturn(Optional.empty());

        newService().start("chan-1", "5001", driver);

        verify(runner, never()).run(any(), any(), any(), any());
        verify(driver).end();
    }
}
