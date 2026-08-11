package com.asteriskia.integration.ad;

import com.asteriskia.domain.config.ConfigService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AdSyncScheduler — job periódico que espelha os usuários do AD em {@code ad_users}, mesmo padrão
 * de {@code AiModelPricingSyncScheduler}/{@code CostAlertScheduler}: roda em intervalo configurável,
 * tolera o AD fora do ar (falha registrada em {@code ad_sync_runs}, não propagada).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdSyncScheduler {

    private final LdapClient ldapClient;
    private final AdUserService adUserService;
    private final AdSyncRunRepository syncRunRepo;
    private final ConfigService config;

    /** Verifica a cada minuto se já passou o intervalo configurado ({@code AD_SYNC_INTERVAL_MINUTES}). */
    @Scheduled(fixedRate = 60_000)
    public void maybeSync() {
        if (!ldapClient.currentConfig().enabled()) {
            return;
        }
        int intervalMinutes = config.getInt("AD_SYNC_INTERVAL_MINUTES", 60);
        var lastRun = syncRunRepo.findFirstByOrderByStartedAtDesc();
        boolean due =
                lastRun.isEmpty()
                        || lastRun.get().getStartedAt().plusMinutes(intervalMinutes).isBefore(
                                LocalDateTime.now());
        if (due) {
            runSync();
        }
    }

    /** Sincronização sob demanda (botão "Sincronizar agora" na tela de administração). */
    public AdSyncRun runSync() {
        AdSyncRun run = syncRunRepo.save(AdSyncRun.builder().startedAt(LocalDateTime.now()).build());
        try {
            var users = ldapClient.fetchAll();
            int count = 0;
            for (var attrs : users) {
                adUserService.upsertMirror(attrs);
                count++;
            }
            run.setStatus(count == users.size() ? AdSyncRun.Status.SUCCESS : AdSyncRun.Status.PARTIAL);
            run.setUsersSynced(count);
            log.info("Sincronização AD concluída: {} usuários", count);
        } catch (Exception e) {
            run.setStatus(AdSyncRun.Status.FAILED);
            run.setErrorMessage(e.getMessage());
            log.warn("Sincronização AD falhou: {}", e.getMessage());
        }
        run.setFinishedAt(LocalDateTime.now());
        return syncRunRepo.save(run);
    }
}
