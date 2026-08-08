package com.asteriskia.domain.callcenter.recording;

import com.asteriskia.telegram.TelegramBotService;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterDiskAlertService — verifica diariamente o percentual de uso do volume de gravações do
 * Call Center e dispara um alerta via Telegram quando ultrapassa o limite configurado. No máximo
 * uma notificação por dia ({@code lastNotifiedDate}) — diferente do alerta de gasto do módulo
 * Financeiro (dedup mensal), disco pode encher em poucos dias e o operador precisa ser avisado de
 * novo no dia seguinte se ainda estiver acima do limite.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterDiskAlertService {

    private final CallCenterDiskAlertConfigRepository configRepository;
    private final TelegramBotService telegramBotService;

    @Value("${app.callcenter.recording-path:/opt/gravacoes/audio}")
    private String recordingBasePath;

    @Transactional(readOnly = true)
    public DiskAlertConfigView getConfig() {
        return toView(loadOrDefault());
    }

    @Transactional
    public DiskAlertConfigView updateConfig(DiskAlertConfigRequest request, String updatedBy) {
        var config = loadOrDefault();
        config.setThresholdPercent(request.thresholdPercent());
        config.setEnabled(request.enabled());
        config.setUpdatedBy(updatedBy);
        configRepository.save(config);
        return toView(config);
    }

    @Transactional
    public void checkAndNotify() {
        var config = loadOrDefault();
        if (!config.isEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (today.equals(config.getLastNotifiedDate())) {
            return;
        }
        Double usagePercent = currentUsagePercent();
        if (usagePercent == null) {
            return;
        }
        if (usagePercent > config.getThresholdPercent()) {
            notify(usagePercent, config.getThresholdPercent());
            config.setLastNotifiedDate(today);
            configRepository.save(config);
        }
    }

    private void notify(double usagePercent, int threshold) {
        telegramBotService.sendMessage(
                String.format(
                        """
                        💾 *Alerta de disco — gravações do Call Center*
                        Uso atual: %.1f%%
                        Limite configurado: %d%%
                        Verifique a retenção em Call Center → Gravações.""",
                        usagePercent, threshold));
        log.info("Alerta de disco de gravações disparado: uso={}% limite={}%", usagePercent, threshold);
    }

    /** null se o caminho configurado não existir/for inacessível (ex: volume ainda não montado). */
    private Double currentUsagePercent() {
        try {
            FileStore store = Files.getFileStore(Path.of(recordingBasePath));
            long total = store.getTotalSpace();
            if (total <= 0) {
                return null;
            }
            long usable = store.getUsableSpace();
            return (1.0 - ((double) usable / total)) * 100.0;
        } catch (IOException | java.nio.file.InvalidPathException e) {
            log.warn("Não foi possível calcular uso de disco de {}: {}", recordingBasePath, e.getMessage());
            return null;
        }
    }

    private CallCenterDiskAlertConfig loadOrDefault() {
        return configRepository.findById("default").orElseGet(CallCenterDiskAlertConfig::new);
    }

    private DiskAlertConfigView toView(CallCenterDiskAlertConfig config) {
        return new DiskAlertConfigView(
                config.getThresholdPercent(),
                config.isEnabled(),
                config.getLastNotifiedDate(),
                currentUsagePercent());
    }
}
