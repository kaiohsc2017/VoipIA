package com.asteriskia.domain.call;

import com.asteriskia.integration.jira.JiraIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JiraSyncScheduler — Sincroniza de volta o status/resolução dos chamados
 * abertos no Jira (Módulo 1). Até esta migration, jira_issue_status era
 * gravado uma única vez na criação e nunca mais atualizado.
 *
 * Escopo limitado às chamadas dos últimos 90 dias sem resolução ainda
 * registrada — evita crescimento ilimitado da varredura em produção.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JiraSyncScheduler {

    private static final int SYNC_WINDOW_DAYS = 90;

    private final CallRecordRepository   callRecordRepository;
    private final JiraIntegrationService jiraService;

    @Scheduled(fixedDelayString = "${app.jira.sync-poll-interval-minutes:15}", timeUnit = TimeUnit.MINUTES)
    public void syncPendingIssues() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(SYNC_WINDOW_DAYS);
            List<CallRecord> pending = callRecordRepository
                .findByJiraIssueKeyIsNotNullAndJiraResolutionIsNullAndCallDateAfter(cutoff);

            if (pending.isEmpty()) return;
            log.debug("JiraSyncScheduler: {} chamados pendentes de sincronização", pending.size());

            for (CallRecord call : pending) {
                try {
                    syncOne(call);
                } catch (Exception e) {
                    log.error("Erro ao sincronizar chamado {} (call id={}): {}",
                        call.getJiraIssueKey(), call.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Erro no ciclo do JiraSyncScheduler: {}", e.getMessage(), e);
        }
    }

    private void syncOne(CallRecord call) {
        jiraService.fetchIssueStatus(call.getJiraIssueKey()).ifPresent(info -> {
            if (info.statusName() != null) {
                call.setJiraIssueStatus(info.statusName());
            }
            call.setJiraResolution(info.resolutionName());
            call.setJiraLastSyncedAt(LocalDateTime.now());
            callRecordRepository.save(call);
        });
    }
}
