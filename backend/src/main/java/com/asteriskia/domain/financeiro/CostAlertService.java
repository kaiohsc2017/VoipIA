package com.asteriskia.domain.financeiro;

import com.asteriskia.domain.call.CallCostService;
import com.asteriskia.domain.call.CallRecordFilter;
import com.asteriskia.domain.call.MonthlyCostSummary;
import com.asteriskia.domain.callcenter.identity.CcIdentityResolutionLogRepository;
import com.asteriskia.domain.callcenter.kb.CcKbAnswerLogRepository;
import com.asteriskia.domain.callcenter.nps.CcSurveyResponseRepository;
import com.asteriskia.domain.insights.InsightMonthlyCostSummary;
import com.asteriskia.domain.insights.InsightsCostFilter;
import com.asteriskia.domain.insights.InsightsCostService;
import com.asteriskia.telegram.TelegramBotService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CostAlertService — configuração e verificação do alerta de gasto em USD por frente do
 * módulo Financeiro (URA/Insights/Análise Sob Demanda). O gasto do mês corrente é calculado
 * sob demanda reaproveitando {@link CallCostService}/{@link InsightsCostService} — nenhuma
 * tabela de histórico própria, o mesmo cálculo já usado pelas telas de Custos IA.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostAlertService {

    // Manter em sincronia manual com os matchers de path exato de
    // /api/v1/financeiro/cost-alerts/{scope} em SecurityConfig.java (um por scope, não
    // wildcard) e com o CHECK constraint de V42__financeiro_cost_alerts.sql (scope
    // 'callcenter' adicionado na V54, Fase 8) — mesmo padrão de sincronia manual já aceito
    // no projeto (ResourceCatalog.java/Sidebar.tsx/AccessGroups.tsx). Adicionar um scope
    // novo aqui sem replicar nos outros 2 lugares deixaria a rota nova cair no
    // anyRequest().authenticated() genérico, sem exigir a permissão financeiro.<scope>.
    private static final List<String> SCOPES =
            List.of(
                    "ura", "insights", "envios", "callcenter", "callcenter_nps", "callcenter_autosservico",
                    "callcenter_identidade");

    private final FinanceiroCostAlertConfigRepository repository;
    private final CallCostService callCostService;
    private final InsightsCostService insightsCostService;
    private final CcSurveyResponseRepository surveyResponseRepository;
    private final CcKbAnswerLogRepository kbAnswerLogRepository;
    private final CcIdentityResolutionLogRepository identityResolutionLogRepository;
    private final TelegramBotService telegramBotService;

    @Transactional(readOnly = true)
    public CostAlertConfigView getConfig(String scope) {
        validateScope(scope);
        return toView(loadOrDefault(scope));
    }

    @Transactional
    public CostAlertConfigView updateConfig(
            String scope, CostAlertConfigRequest request, String updatedBy) {
        validateScope(scope);
        FinanceiroCostAlertConfig config = loadOrDefault(scope);
        config.setThresholdUsd(request.thresholdUsd());
        config.setEnabled(request.enabled());
        config.setUpdatedBy(updatedBy);
        repository.save(config);
        return toView(config);
    }

    /**
     * Executado pelo scheduler diário — para cada frente habilitada, dispara um alerta via
     * Telegram se o gasto do mês corrente ultrapassar o limite configurado. No máximo uma
     * notificação por frente por mês (dedup via {@code lastNotifiedMonth}).
     */
    @Transactional
    public void checkAndNotify() {
        String currentMonth = YearMonth.now().toString();
        for (FinanceiroCostAlertConfig config : repository.findAllById(SCOPES)) {
            if (!config.isEnabled() || currentMonth.equals(config.getLastNotifiedMonth())) {
                continue;
            }
            BigDecimal spend = currentMonthSpend(config.getScope());
            if (spend.compareTo(config.getThresholdUsd()) > 0) {
                notify(config.getScope(), spend, config.getThresholdUsd());
                config.setLastNotifiedMonth(currentMonth);
                repository.save(config);
            }
        }
    }

    private BigDecimal currentMonthSpend(String scope) {
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        return switch (scope) {
            case "ura" -> sum(
                    callCostService.summarizeByMonth(
                            new CallRecordFilter(
                                    null, null, null, null, null, null, null, null,
                                    monthStart, now, null, null)),
                    MonthlyCostSummary::totalCostUsd);
            case "insights" -> sum(
                    insightsCostService.summarizeByMonth(
                            new InsightsCostFilter(monthStart, now, null, "verint", null)),
                    InsightMonthlyCostSummary::totalCostUsd);
            case "envios" -> sum(
                    insightsCostService.summarizeByMonth(
                            new InsightsCostFilter(monthStart, now, null, "upload", null)),
                    InsightMonthlyCostSummary::totalCostUsd);
            case "callcenter" -> sum(
                    insightsCostService.summarizeByMonth(
                            new InsightsCostFilter(monthStart, now, null, "callcenter", null)),
                    InsightMonthlyCostSummary::totalCostUsd);
            case "callcenter_nps" -> surveyResponseRepository.sumAiCostUsdBetween(monthStart, now);
            case "callcenter_autosservico" -> kbAnswerLogRepository.sumCostUsdBetween(monthStart, now);
            case "callcenter_identidade" -> identityResolutionLogRepository.sumAiCostUsdBetween(monthStart, now);
            default -> throw invalidScope(scope);
        };
    }

    private void notify(String scope, BigDecimal spend, BigDecimal threshold) {
        String label = labelFor(scope);
        telegramBotService.sendMessage(
                String.format(
                        """
                        💰 *Limite de gasto de IA atingido — %s*
                        Gasto no mês: US$ %s
                        Limite configurado: US$ %s
                        Confira em Financeiro → %s.""",
                        label, spend, threshold, label));
        log.info(
                "Alerta de gasto de IA disparado para '{}': gasto=US${} limite=US${}",
                scope, spend, threshold);
    }

    private FinanceiroCostAlertConfig loadOrDefault(String scope) {
        return repository
                .findById(scope)
                .orElseGet(() -> FinanceiroCostAlertConfig.builder().scope(scope).build());
    }

    private CostAlertConfigView toView(FinanceiroCostAlertConfig config) {
        return new CostAlertConfigView(
                config.getScope(),
                config.getThresholdUsd(),
                config.isEnabled(),
                config.getLastNotifiedMonth(),
                currentMonthSpend(config.getScope()));
    }

    private static <T> BigDecimal sum(List<T> items, Function<T, BigDecimal> extractor) {
        return items.stream().map(extractor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void validateScope(String scope) {
        if (!SCOPES.contains(scope)) {
            throw invalidScope(scope);
        }
    }

    private static ResponseStatusException invalidScope(String scope) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope inválido: " + scope);
    }

    private static String labelFor(String scope) {
        return switch (scope) {
            case "ura" -> "URA";
            case "insights" -> "Insights";
            case "envios" -> "Análise Sob Demanda";
            case "callcenter" -> "Call Center";
            case "callcenter_nps" -> "Pesquisa de Satisfação (NPS)";
            case "callcenter_autosservico" -> "Autosserviço (Base de Conhecimento)";
            case "callcenter_identidade" -> "Identidade do Contato (Fase 14)";
            default -> scope;
        };
    }
}
