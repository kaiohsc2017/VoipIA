package com.asteriskia.domain.callcenter.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterRecordingRetentionServiceTest — teste de fronteira do expurgo (limite exato NÃO
 * purga, um dia além purga) e idempotência de rodar o expurgo duas vezes seguidas.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterRecordingRetentionServiceTest {

    @Mock private CcRecordingRepository recordingRepository;
    @Mock private CallCenterRecordingRetentionConfigRepository configRepository;
    @Mock private CallCenterRecordingService recordingService;

    private CallCenterRecordingRetentionService newService() {
        return new CallCenterRecordingRetentionService(recordingRepository, configRepository, recordingService);
    }

    @Test
    @DisplayName("purgeExpired: gravação exatamente no limite de retenção NÃO é purgada")
    void purgeExpired_exactlyAtLimit_notPurged() {
        var service = newService();
        var config = CallCenterRecordingRetentionConfig.builder().retentionDays(30).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));
        // findByStartedAtBefore já exclui por definição quem está exatamente no corte
        // (repositório usa "<", não "<="): simula o repositório retornando lista vazia.
        when(recordingRepository.findByStartedAtBefore(any())).thenReturn(List.of());

        var result = service.purgeExpired();

        assertThat(result.deletedCount()).isZero();
        verify(recordingRepository, never()).delete(any(CcRecording.class));
    }

    @Test
    @DisplayName("purgeExpired: gravação um dia além do limite é purgada (registro + arquivo físico)")
    void purgeExpired_oneDayBeyondLimit_purgesRecordAndFile() throws Exception {
        var service = newService();
        var config = CallCenterRecordingRetentionConfig.builder().retentionDays(30).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        var expired = CcRecording.builder().id(1L).startedAt(LocalDateTime.now().minusDays(31)).build();
        when(recordingRepository.findByStartedAtBefore(any())).thenReturn(List.of(expired));

        var tempFile = java.io.File.createTempFile("cc-recording-test", ".wav");
        when(recordingService.resolveAudioFile(expired)).thenReturn(tempFile);

        var result = service.purgeExpired();

        assertThat(result.deletedCount()).isEqualTo(1);
        assertThat(tempFile).doesNotExist();
        verify(recordingRepository).delete(expired);
        verify(configRepository).save(eq(config));
        assertThat(config.getLastPurgeDeletedCount()).isEqualTo(1);
        assertThat(config.getLastPurgeAt()).isNotNull();
    }

    @Test
    @DisplayName("purgeExpired: falha ao remover o arquivo físico mantém o registro (não cria órfão)")
    void purgeExpired_fileDeletionFails_keepsRecordForRetry() throws Exception {
        var service = newService();
        var config = CallCenterRecordingRetentionConfig.builder().retentionDays(30).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        var expired = CcRecording.builder().id(1L).startedAt(LocalDateTime.now().minusDays(31)).build();
        when(recordingRepository.findByStartedAtBefore(any())).thenReturn(List.of(expired));

        // File.delete() em um diretório não-vazio retorna false sem lançar exceção — mesma
        // classe de falha silenciosa (permissão, handle aberto) que o achado de code review
        // cobre: a linha do banco não pode ser removida se o arquivo físico não foi.
        var nonEmptyDir = java.nio.file.Files.createTempDirectory("cc-recording-test-dir").toFile();
        java.io.File.createTempFile("child", ".tmp", nonEmptyDir);
        when(recordingService.resolveAudioFile(expired)).thenReturn(nonEmptyDir);

        var result = service.purgeExpired();

        assertThat(result.deletedCount()).isZero();
        verify(recordingRepository, never()).delete(any(CcRecording.class));
        assertThat(config.getLastPurgeDeletedCount()).isZero();

        for (java.io.File child : nonEmptyDir.listFiles()) {
            child.delete();
        }
        nonEmptyDir.delete();
    }

    @Test
    @DisplayName("purgeExpired: rodar duas vezes seguidas não duplica exclusão nem quebra")
    void purgeExpired_runTwiceInARow_secondRunFindsNothing() {
        var service = newService();
        var config = CallCenterRecordingRetentionConfig.builder().retentionDays(30).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        var expired = CcRecording.builder().id(1L).startedAt(LocalDateTime.now().minusDays(31)).build();
        when(recordingRepository.findByStartedAtBefore(any()))
                .thenReturn(List.of(expired))
                .thenReturn(List.of());
        when(recordingService.resolveAudioFile(expired)).thenReturn(null);

        var first = service.purgeExpired();
        var second = service.purgeExpired();

        assertThat(first.deletedCount()).isEqualTo(1);
        assertThat(second.deletedCount()).isZero();
        verify(recordingRepository, times(1)).delete(expired);
    }
}
