package com.asteriskia.domain.callcenter.cobrowsing;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterCobrowseRetentionService — expurgo automático de sessões de co-browsing além do
 * prazo de retenção configurado (linha única em {@code cc_cobrowse_retention_config}, 60 meses
 * default). Mirror de {@code CallCenterRecordingRetentionService} (voz), com uma diferença
 * deliberada de modelo (já registrada em 17c, plano §5.5): aqui a linha do banco **nunca** é
 * apagada — só {@code purged_at} é marcado, preservando o histórico/auditoria mesmo depois do
 * arquivo físico sumir. Reusa {@link CallCenterCobrowsingService#purge} tal como já existe
 * (mesma lógica usada pela eliminação sob demanda de 17c) — sem duplicar a defesa de path
 * traversal nem o tratamento de falha de I/O.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterCobrowseRetentionService {

    private final CcCobrowseSessionRepository sessionRepository;
    private final CcCobrowseRetentionConfigRepository configRepository;
    private final CallCenterCobrowsingService cobrowsingService;

    @Transactional(readOnly = true)
    public CobrowseRetentionConfigView getConfig() {
        return toView(loadOrDefault());
    }

    @Transactional
    public CobrowseRetentionConfigView updateConfig(
            CobrowseRetentionConfigRequest request, String updatedBy) {
        var config = loadOrDefault();
        config.setRetentionDays(request.retentionDays());
        config.setUpdatedBy(updatedBy);
        configRepository.save(config);
        return toView(config);
    }

    /**
     * Expurga (apaga arquivo físico + marca {@code purged_at}, nunca a linha) as sessões com
     * {@code started_at} anterior ao corte de retenção e ainda não purgadas. Processa uma a uma,
     * capturando qualquer exceção por item — uma falha de I/O isolada nunca impede o expurgo das
     * demais sessões do lote.
     */
    @Transactional
    public CobrowseRetentionRunResult purgeExpired() {
        var config = loadOrDefault();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(config.getRetentionDays());
        List<CcCobrowseSession> expired = sessionRepository.findByStartedAtBeforeAndPurgedAtIsNull(cutoff);

        int purgedCount = 0;
        for (CcCobrowseSession session : expired) {
            try {
                cobrowsingService.purge(session);
                purgedCount++;
            } catch (RuntimeException e) {
                log.warn(
                        "Falha ao expurgar sessão de co-browsing id={}: {} — mantida para nova"
                                + " tentativa no próximo ciclo",
                        session.getId(),
                        e.getMessage());
            }
        }

        config.setLastPurgeAt(LocalDateTime.now());
        config.setLastPurgeDeletedCount(purgedCount);
        configRepository.save(config);

        log.info(
                "Expurgo de retenção de co-browsing: {} sessão(ões) expurgada(s) de {} candidata(s)"
                        + " (corte={})",
                purgedCount,
                expired.size(),
                cutoff);
        return new CobrowseRetentionRunResult(purgedCount);
    }

    private CcCobrowseRetentionConfig loadOrDefault() {
        return configRepository.findById("default").orElseGet(CcCobrowseRetentionConfig::new);
    }

    private static CobrowseRetentionConfigView toView(CcCobrowseRetentionConfig config) {
        return new CobrowseRetentionConfigView(
                config.getRetentionDays(), config.getLastPurgeAt(), config.getLastPurgeDeletedCount());
    }
}
