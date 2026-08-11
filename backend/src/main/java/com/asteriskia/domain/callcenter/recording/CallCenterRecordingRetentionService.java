package com.asteriskia.domain.callcenter.recording;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterRecordingRetentionService — expurga gravações mais antigas que o prazo de retenção
 * configurado (linha única em {@code cc_recording_retention_config}), removendo tanto o registro
 * em {@code cc_recordings} quanto o arquivo físico correspondente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterRecordingRetentionService {

    private final CcRecordingRepository recordingRepository;
    private final CallCenterRecordingRetentionConfigRepository configRepository;
    private final CallCenterRecordingService recordingService;

    @Transactional(readOnly = true)
    public RetentionConfigView getConfig() {
        return toView(loadOrDefault());
    }

    @Transactional
    public RetentionConfigView updateConfig(RetentionConfigRequest request, String updatedBy) {
        var config = loadOrDefault();
        config.setRetentionDays(request.retentionDays());
        config.setUpdatedBy(updatedBy);
        configRepository.save(config);
        return toView(config);
    }

    /**
     * Deleta as gravações com {@code started_at} estritamente anterior ao corte de retenção — no
     * limite exato (started_at == cutoff) a gravação NÃO é purgada, só um dia além.
     *
     * <p>Achado de code review: a versão anterior deletava a linha do banco mesmo quando
     * {@code File.delete()} falhava (retorna {@code false} sem lançar exceção — ex: permissão,
     * arquivo aberto por streaming concorrente), criando um arquivo órfão permanente sem nenhuma
     * linha em {@code cc_recordings} para reconciliar depois. Agora o registro só é removido do
     * banco se o arquivo físico foi de fato apagado (ou já não existia) — falhas ficam retidas
     * para nova tentativa no próximo ciclo, e a contagem retornada reflete o que falhou.
     */
    @Transactional
    public RetentionRunResult purgeExpired() {
        var config = loadOrDefault();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(config.getRetentionDays());
        List<CcRecording> expired = recordingRepository.findByStartedAtBefore(cutoff);

        int deletedCount = 0;
        int failedCount = 0;
        for (CcRecording recording : expired) {
            if (deleteFileIfExists(recording)) {
                recordingRepository.delete(recording);
                deletedCount++;
            } else {
                failedCount++;
            }
        }

        config.setLastPurgeAt(LocalDateTime.now());
        config.setLastPurgeDeletedCount(deletedCount);
        configRepository.save(config);

        log.info(
                "Expurgo de retenção do Call Center: {} gravações removidas, {} falharam (corte={})",
                deletedCount,
                failedCount,
                cutoff);
        return new RetentionRunResult(deletedCount);
    }

    /** @return true se o arquivo foi removido com sucesso ou já não existia; false se a deleção falhou. */
    private boolean deleteFileIfExists(CcRecording recording) {
        File file = recordingService.resolveAudioFile(recording);
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.delete()) {
            return true;
        }
        log.warn("Não foi possível remover o arquivo físico da gravação id={}: {}", recording.getId(), file);
        return false;
    }

    private CallCenterRecordingRetentionConfig loadOrDefault() {
        return configRepository.findById("default").orElseGet(CallCenterRecordingRetentionConfig::new);
    }

    private static RetentionConfigView toView(CallCenterRecordingRetentionConfig config) {
        return new RetentionConfigView(
                config.getRetentionDays(), config.getLastPurgeAt(), config.getLastPurgeDeletedCount());
    }
}
