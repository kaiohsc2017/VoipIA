package com.asteriskia.domain.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.call.CallCostService;
import com.asteriskia.domain.call.MonthlyCostSummary;
import com.asteriskia.domain.callcenter.copilot.CcContactProfileRepository;
import com.asteriskia.domain.callcenter.identity.CcIdentityResolutionLogRepository;
import com.asteriskia.domain.callcenter.kb.CcKbAnswerLogRepository;
import com.asteriskia.domain.callcenter.nps.CcSurveyResponseRepository;
import com.asteriskia.domain.insights.InsightMonthlyCostSummary;
import com.asteriskia.domain.insights.InsightsCostService;
import com.asteriskia.telegram.TelegramBotService;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * CostAlertServiceTest — cobre os 3 cenários centrais do alerta de gasto de IA: limite
 * atingido (dispara Telegram + marca o mês), gasto abaixo do limite (não dispara) e frente
 * já notificada no mês corrente (não dispara de novo mesmo com o gasto acima do limite).
 */
@ExtendWith(MockitoExtension.class)
class CostAlertServiceTest {

    @Mock private FinanceiroCostAlertConfigRepository repository;
    @Mock private CallCostService callCostService;
    @Mock private InsightsCostService insightsCostService;
    @Mock private CcSurveyResponseRepository surveyResponseRepository;
    @Mock private CcKbAnswerLogRepository kbAnswerLogRepository;
    @Mock private CcIdentityResolutionLogRepository identityResolutionLogRepository;
    @Mock private CcContactProfileRepository contactProfileRepository;
    @Mock private TelegramBotService telegramBotService;

    private CostAlertService service;

    @BeforeEach
    void setUp() {
        service = new CostAlertService(
                repository, callCostService, insightsCostService, surveyResponseRepository,
                kbAnswerLogRepository, identityResolutionLogRepository, contactProfileRepository,
                telegramBotService);
    }

    private static FinanceiroCostAlertConfig config(
            String scope, double threshold, boolean enabled, String lastNotifiedMonth) {
        return FinanceiroCostAlertConfig.builder()
                .scope(scope)
                .thresholdUsd(BigDecimal.valueOf(threshold))
                .enabled(enabled)
                .lastNotifiedMonth(lastNotifiedMonth)
                .build();
    }

    @Test
    void checkAndNotify_gastoAcimaDoLimite_enviaAlertaEMarcaMesNotificado() {
        FinanceiroCostAlertConfig uraConfig = config("ura", 10.0, true, null);
        when(repository.findAllById(anyIterable())).thenReturn(List.of(uraConfig));
        when(callCostService.summarizeByMonth(any()))
                .thenReturn(List.of(new MonthlyCostSummary(
                        YearMonth.now().toString(), BigDecimal.ZERO, BigDecimal.valueOf(15), BigDecimal.ZERO,
                        BigDecimal.valueOf(15), 3)));

        service.checkAndNotify();

        verify(telegramBotService, times(1)).sendMessage(any());
        assertThat(uraConfig.getLastNotifiedMonth()).isEqualTo(YearMonth.now().toString());
        verify(repository).save(uraConfig);
    }

    @Test
    void checkAndNotify_gastoAbaixoDoLimite_naoEnviaAlerta() {
        FinanceiroCostAlertConfig uraConfig = config("ura", 100.0, true, null);
        when(repository.findAllById(anyIterable())).thenReturn(List.of(uraConfig));
        when(callCostService.summarizeByMonth(any()))
                .thenReturn(List.of(new MonthlyCostSummary(
                        YearMonth.now().toString(), BigDecimal.ZERO, BigDecimal.valueOf(5), BigDecimal.ZERO,
                        BigDecimal.valueOf(5), 1)));

        service.checkAndNotify();

        verify(telegramBotService, never()).sendMessage(any());
        assertThat(uraConfig.getLastNotifiedMonth()).isNull();
    }

    @Test
    void checkAndNotify_jaNotificadoNesteMes_naoEnviaDeNovoMesmoAcimaDoLimite() {
        FinanceiroCostAlertConfig uraConfig = config("ura", 10.0, true, YearMonth.now().toString());
        when(repository.findAllById(anyIterable())).thenReturn(List.of(uraConfig));

        service.checkAndNotify();

        verify(telegramBotService, never()).sendMessage(any());
        verify(callCostService, never()).summarizeByMonth(any());
    }

    @Test
    void checkAndNotify_desabilitado_naoConsultaGastoNemNotifica() {
        FinanceiroCostAlertConfig uraConfig = config("ura", 10.0, false, null);
        when(repository.findAllById(anyIterable())).thenReturn(List.of(uraConfig));

        service.checkAndNotify();

        verify(telegramBotService, never()).sendMessage(any());
        verify(callCostService, never()).summarizeByMonth(any());
    }

    @Test
    void getConfig_somaGastoDeInsightsUsandoOMirrorSemTts() {
        when(repository.findById("insights")).thenReturn(Optional.empty());
        when(insightsCostService.summarizeByMonth(any()))
                .thenReturn(List.of(new InsightMonthlyCostSummary(
                        YearMonth.now().toString(), BigDecimal.valueOf(2), BigDecimal.valueOf(3),
                        BigDecimal.valueOf(5), 2)));

        CostAlertConfigView view = service.getConfig("insights");

        assertThat(view.scope()).isEqualTo("insights");
        assertThat(view.enabled()).isFalse();
        assertThat(view.currentMonthSpendUsd()).isEqualByComparingTo(BigDecimal.valueOf(5));
    }

    @Test
    void getConfig_somaGastoDeCallcenterUsandoSourceCallcenter() {
        when(repository.findById("callcenter")).thenReturn(Optional.empty());
        when(insightsCostService.summarizeByMonth(
                        argThat(f -> f != null && "callcenter".equals(f.source()))))
                .thenReturn(List.of(new InsightMonthlyCostSummary(
                        YearMonth.now().toString(), BigDecimal.valueOf(1), BigDecimal.valueOf(1),
                        BigDecimal.valueOf(2), 1)));

        CostAlertConfigView view = service.getConfig("callcenter");

        assertThat(view.scope()).isEqualTo("callcenter");
        assertThat(view.currentMonthSpendUsd()).isEqualByComparingTo(BigDecimal.valueOf(2));
    }

    @Test
    void getConfig_somaGastoDeCallcenterAutosservicoUsandoAnswerLog() {
        when(repository.findById("callcenter_autosservico")).thenReturn(Optional.empty());
        when(kbAnswerLogRepository.sumCostUsdBetween(any(), any())).thenReturn(BigDecimal.valueOf(1.5));

        CostAlertConfigView view = service.getConfig("callcenter_autosservico");

        assertThat(view.scope()).isEqualTo("callcenter_autosservico");
        assertThat(view.currentMonthSpendUsd()).isEqualByComparingTo(BigDecimal.valueOf(1.5));
    }

    @Test
    void getConfig_scopeInvalido_lancaBadRequest() {
        assertThatThrownBy(() -> service.getConfig("invalido"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("scope inválido");
    }

    @Test
    void updateConfig_persisteThresholdEnabledEUpdatedBy() {
        when(repository.findById("ura")).thenReturn(Optional.empty());
        when(callCostService.summarizeByMonth(any())).thenReturn(List.of());
        CostAlertConfigRequest request = new CostAlertConfigRequest(BigDecimal.valueOf(50), true);

        CostAlertConfigView view = service.updateConfig("ura", request, "kaio");

        assertThat(view.scope()).isEqualTo("ura");
        assertThat(view.thresholdUsd()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(view.enabled()).isTrue();
        verify(repository).save(argThat(saved ->
                saved.getScope().equals("ura")
                        && saved.getThresholdUsd().compareTo(BigDecimal.valueOf(50)) == 0
                        && saved.isEnabled()
                        && "kaio".equals(saved.getUpdatedBy())));
    }

    @Test
    void updateConfig_scopeInvalido_lancaBadRequest() {
        CostAlertConfigRequest request = new CostAlertConfigRequest(BigDecimal.TEN, true);

        assertThatThrownBy(() -> service.updateConfig("invalido", request, "kaio"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("scope inválido");
    }
}
