package com.asteriskia.domain.financeiro;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CostAlertScheduler — dispara o alerta de gasto de IA (Telegram) quando o gasto do mês
 * corrente de uma frente do módulo Financeiro ultrapassa o limite configurado. Mirror de
 * {@code AiModelPricingSyncScheduler}: job agendado + método público {@code run()},
 * reaproveitável por um endpoint manual se necessário no futuro.
 */
@Component
@RequiredArgsConstructor
public class CostAlertScheduler {

    private final CostAlertService service;

    @Scheduled(cron = "${app.financeiro.cost-alert-cron:0 0 8 * * ?}")
    public void scheduledCheck() {
        run();
    }

    public void run() {
        service.checkAndNotify();
    }
}
