package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.chat.TelegramApiClient;
import com.asteriskia.domain.settings.EmailSenderService;
import com.asteriskia.domain.settings.EnvFileStore;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre a regra de "devido" (sub-fase 9c.6): frequência DAILY/WEEKLY/MONTHLY + hora do dia, nunca
 * roda duas vezes no mesmo dia, e a entrega fail-closed por e-mail quando EMAIL_ENABLED=false.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterReportScheduleServiceTest {

    @Mock
    private CcReportScheduleRepository scheduleRepository;
    @Mock
    private CallCenterReportExportService exportService;
    @Mock
    private TelegramApiClient telegramApiClient;
    @Mock
    private EmailSenderService emailSenderService;
    @Mock
    private EnvFileStore envFileStore;

    private CallCenterReportScheduleService service;

    @BeforeEach
    void setUp() {
        service = new CallCenterReportScheduleService(
                scheduleRepository, exportService, telegramApiClient, emailSenderService, envFileStore);
    }

    private CcReportSchedule schedule(String frequency, Integer dayOfWeek, Integer dayOfMonth, int hourOfDay,
            OffsetDateTime lastRunAt, String channel) {
        return CcReportSchedule.builder()
                .id(1L).name("Teste").reportType("CALLS_EXCEL").periodDays(7)
                .frequency(frequency).dayOfWeek(dayOfWeek).dayOfMonth(dayOfMonth).hourOfDay(hourOfDay)
                .channel(channel).recipient("dest").active(true).lastRunAt(lastRunAt).build();
    }

    @Test
    @DisplayName("DAILY dispara na hora configurada, mesmo sem ter rodado antes")
    void runDue_daily_firesAtConfiguredHour() throws Exception {
        CcReportSchedule s = schedule("DAILY", null, null, 8, null, "telegram");
        when(scheduleRepository.findByActiveTrue()).thenReturn(List.of(s));
        when(envFileStore.readRaw()).thenReturn(Map.of("CALLCENTER_TELEGRAM_BOT_TOKEN", "tok"));
        when(exportService.exportCallsExcel(any(), any())).thenReturn(new byte[]{1});
        when(telegramApiClient.sendDocument(anyString(), anyString(), any(), anyString())).thenReturn(true);

        service.runDue(LocalDateTime.of(2026, 8, 14, 8, 0));

        verify(telegramApiClient).sendDocument(anyString(), anyString(), any(), anyString());
        ArgumentCaptor<CcReportSchedule> captor = ArgumentCaptor.forClass(CcReportSchedule.class);
        verify(scheduleRepository).save(captor.capture());
        assertThat(captor.getValue().getLastRunStatus()).isEqualTo("OK");
    }

    @Test
    @DisplayName("não dispara de novo se já rodou hoje")
    void runDue_skipsIfAlreadyRunToday() {
        OffsetDateTime lastRun = LocalDateTime.of(2026, 8, 14, 8, 0).atOffset(java.time.ZoneOffset.UTC);
        CcReportSchedule s = schedule("DAILY", null, null, 8, lastRun, "telegram");
        when(scheduleRepository.findByActiveTrue()).thenReturn(List.of(s));

        service.runDue(LocalDateTime.of(2026, 8, 14, 8, 0));

        verify(exportService, never()).exportCallsExcel(any(), any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("WEEKLY só dispara no dia da semana configurado")
    void runDue_weekly_onlyOnConfiguredDayOfWeek() {
        // 2026-08-14 é sexta-feira (dayOfWeek=5); agendado para segunda (1) — não deve disparar
        CcReportSchedule s = schedule("WEEKLY", 1, null, 8, null, "telegram");
        when(scheduleRepository.findByActiveTrue()).thenReturn(List.of(s));

        service.runDue(LocalDateTime.of(2026, 8, 14, 8, 0));

        verify(exportService, never()).exportCallsExcel(any(), any());
    }

    @Test
    @DisplayName("e-mail fail-closed quando EMAIL_ENABLED está desligado — nunca chama send()")
    void runDue_email_failsClosedWhenDisabled() {
        CcReportSchedule s = schedule("DAILY", null, null, 8, null, "email");
        when(scheduleRepository.findByActiveTrue()).thenReturn(List.of(s));
        when(exportService.exportCallsExcel(any(), any())).thenReturn(new byte[]{1});
        when(emailSenderService.isEnabled()).thenReturn(false);

        service.runDue(LocalDateTime.of(2026, 8, 14, 8, 0));

        verify(emailSenderService, never()).send(any(), any(), any(), any(), any());
        ArgumentCaptor<CcReportSchedule> captor = ArgumentCaptor.forClass(CcReportSchedule.class);
        verify(scheduleRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getLastRunStatus()).isEqualTo("FAILED");
    }
}
