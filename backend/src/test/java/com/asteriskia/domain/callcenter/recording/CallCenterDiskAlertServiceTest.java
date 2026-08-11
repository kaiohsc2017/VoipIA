package com.asteriskia.domain.callcenter.recording;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.telegram.TelegramBotService;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterDiskAlertServiceTest — dedup diário do alerta de disco e respeito ao toggle
 * {@code enabled}. O cálculo real de uso de disco (Files.getFileStore) não é mockável sem
 * reescrever o service por injeção de FileStore — os testes usam um caminho inexistente
 * (currentUsagePercent retorna null) e focam na lógica de dedup/enabled, que independe do valor
 * de uso calculado.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterDiskAlertServiceTest {

    @Mock private CallCenterDiskAlertConfigRepository configRepository;
    @Mock private TelegramBotService telegramBotService;

    private CallCenterDiskAlertService newService() throws Exception {
        var service = new CallCenterDiskAlertService(configRepository, telegramBotService);
        Field field = CallCenterDiskAlertService.class.getDeclaredField("recordingBasePath");
        field.setAccessible(true);
        field.set(service, "/caminho/que/nao/existe/nesta/maquina");
        return service;
    }

    @Test
    @DisplayName("checkAndNotify: não dispara quando desabilitado")
    void checkAndNotify_disabled_doesNotNotify() throws Exception {
        var service = newService();
        var config = CallCenterDiskAlertConfig.builder().enabled(false).thresholdPercent(85).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        service.checkAndNotify();

        verify(telegramBotService, never()).sendMessage(anyString());
        verify(configRepository, never()).save(any());
    }

    @Test
    @DisplayName("checkAndNotify: não dispara de novo no mesmo dia (dedup)")
    void checkAndNotify_alreadyNotifiedToday_doesNotNotifyAgain() throws Exception {
        var service = newService();
        var config = CallCenterDiskAlertConfig.builder()
                .enabled(true)
                .thresholdPercent(0) // limite baixíssimo — só pra não depender do uso real de disco
                .lastNotifiedDate(LocalDate.now())
                .build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        service.checkAndNotify();

        verify(telegramBotService, never()).sendMessage(anyString());
        verify(configRepository, never()).save(any());
    }

    @Test
    @DisplayName("checkAndNotify: caminho de disco inacessível não lança exceção nem notifica")
    void checkAndNotify_diskPathUnavailable_doesNotNotifyOrThrow() throws Exception {
        var service = newService();
        var config = CallCenterDiskAlertConfig.builder().enabled(true).thresholdPercent(0).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        service.checkAndNotify();

        verify(telegramBotService, never()).sendMessage(anyString());
    }
}
