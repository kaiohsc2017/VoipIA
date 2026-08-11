package com.asteriskia.domain.financeiro;

import java.math.BigDecimal;

/**
 * CostAlertConfigView — configuração do alerta de gasto de uma frente + gasto do mês
 * corrente já calculado (conveniência para a UI mostrar o progresso contra o limite sem
 * uma segunda chamada).
 */
public record CostAlertConfigView(
        String scope,
        BigDecimal thresholdUsd,
        boolean enabled,
        String lastNotifiedMonth,
        BigDecimal currentMonthSpendUsd) {}
