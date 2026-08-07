package com.asteriskia.domain.callcenter.supervision;

import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.telegram.TelegramBotService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterSlaAlertService — alerta via Telegram quando uma fila cruza o limiar de espera
 * máxima e/ou nível de serviço mínimo configurado (Fase 6). Granularidade diária — mesmo padrão
 * do alerta de disco de gravações (V49): uma fila pode ficar acima do limite por vários dias.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterSlaAlertService {

    private final CcQueueAlertConfigRepository configRepository;
    private final CcQueueRepository queueRepository;
    private final CallCenterSupervisionPanelService panelService;
    private final TelegramBotService telegramBotService;

    @Transactional(readOnly = true)
    public QueueAlertConfigView getConfig(Long queueId) {
        return QueueAlertConfigView.from(loadOrDefault(queueId));
    }

    @Transactional
    public QueueAlertConfigView updateConfig(Long queueId, QueueAlertConfigRequest request, String updatedBy) {
        queueRepository
                .findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Fila não encontrada: " + queueId));
        var config = loadOrDefault(queueId);
        config.setMaxWaitingCount(request.maxWaitingCount());
        config.setMinServiceLevelPercent(request.minServiceLevelPercent());
        config.setEnabled(request.enabled());
        config.setUpdatedBy(updatedBy);
        configRepository.save(config);
        return QueueAlertConfigView.from(config);
    }

    @Transactional
    public void checkAndNotify() {
        var today = LocalDate.now();
        List<CcQueueAlertConfig> configs = configRepository.findByEnabledTrue();
        if (configs.isEmpty()) {
            return;
        }
        var snapshot = panelService.snapshot();
        for (var config : configs) {
            if (today.equals(config.getLastNotifiedDate())) {
                continue;
            }
            snapshot.queues().stream()
                    .filter(q -> q.queueId().equals(config.getQueueId()))
                    .findFirst()
                    .filter(q -> exceedsThreshold(q, config))
                    .ifPresent(
                            q -> {
                                notify(q, config);
                                config.setLastNotifiedDate(today);
                                configRepository.save(config);
                            });
        }
    }

    private boolean exceedsThreshold(QueueSupervisionView view, CcQueueAlertConfig config) {
        var waitingExceeded =
                config.getMaxWaitingCount() != null && view.waitingCount() > config.getMaxWaitingCount();
        var serviceLevelExceeded =
                config.getMinServiceLevelPercent() != null
                        && view.serviceLevelPercent() != null
                        && view.serviceLevelPercent() < config.getMinServiceLevelPercent();
        return waitingExceeded || serviceLevelExceeded;
    }

    private void notify(QueueSupervisionView view, CcQueueAlertConfig config) {
        telegramBotService.sendMessage(
                String.format(
                        """
                        📞 *Alerta de SLA — fila %s*
                        Em espera agora: %d (limite: %s)
                        Nível de serviço hoje: %s (limite: %s)
                        Verifique o painel de Supervisão do Call Center.""",
                        view.displayName(),
                        view.waitingCount(),
                        config.getMaxWaitingCount() == null ? "—" : config.getMaxWaitingCount(),
                        view.serviceLevelPercent() == null
                                ? "—"
                                : String.format("%.0f%%", view.serviceLevelPercent()),
                        config.getMinServiceLevelPercent() == null ? "—" : config.getMinServiceLevelPercent() + "%"));
        log.info("Alerta de SLA disparado: fila={} waiting={}", view.queueName(), view.waitingCount());
    }

    private CcQueueAlertConfig loadOrDefault(Long queueId) {
        return configRepository.findById(queueId).orElseGet(() -> CcQueueAlertConfig.builder().queueId(queueId).build());
    }
}
