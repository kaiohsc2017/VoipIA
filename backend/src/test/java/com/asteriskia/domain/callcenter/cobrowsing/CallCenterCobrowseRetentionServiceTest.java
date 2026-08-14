package com.asteriskia.domain.callcenter.cobrowsing;

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
 * CallCenterCobrowseRetentionServiceTest — Fase 17d. Cobre o corte de 60 meses (default), a
 * preservação da linha do banco mesmo quando um item falha, e a idempotência de rodar o expurgo
 * duas vezes seguidas (mesmo espírito de {@code CallCenterRecordingRetentionServiceTest}, com a
 * diferença deliberada de modelo: aqui a linha nunca é apagada, só {@code purged_at}).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterCobrowseRetentionServiceTest {

    @Mock private CcCobrowseSessionRepository sessionRepository;
    @Mock private CcCobrowseRetentionConfigRepository configRepository;
    @Mock private CallCenterCobrowsingService cobrowsingService;

    private CallCenterCobrowseRetentionService newService() {
        return new CallCenterCobrowseRetentionService(sessionRepository, configRepository, cobrowsingService);
    }

    private CcCobrowseSession session(long id, int daysAgo) {
        return CcCobrowseSession.builder()
                .id(id)
                .chatSessionId(id)
                .startedAt(LocalDateTime.now().minusDays(daysAgo))
                .build();
    }

    @Test
    @DisplayName("purgeExpired: sem sessões além do corte, nada é expurgado")
    void purgeExpired_noExpiredSessions_purgesNothing() {
        var service = newService();
        var config = CcCobrowseRetentionConfig.builder().retentionDays(1826).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));
        when(sessionRepository.findByStartedAtBeforeAndPurgedAtIsNull(any())).thenReturn(List.of());

        var result = service.purgeExpired();

        assertThat(result.purgedCount()).isZero();
        verify(cobrowsingService, never()).purge(any());
        assertThat(config.getLastPurgeDeletedCount()).isZero();
        assertThat(config.getLastPurgeAt()).isNotNull();
    }

    @Test
    @DisplayName("purgeExpired: sessão além do corte de retenção é expurgada via CallCenterCobrowsingService.purge")
    void purgeExpired_sessionBeyondRetention_purgesViaService() {
        var service = newService();
        var config = CcCobrowseRetentionConfig.builder().retentionDays(1826).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        var expired = session(1L, 1827);
        when(sessionRepository.findByStartedAtBeforeAndPurgedAtIsNull(any())).thenReturn(List.of(expired));
        when(cobrowsingService.purge(expired)).thenReturn(expired);

        var result = service.purgeExpired();

        assertThat(result.purgedCount()).isEqualTo(1);
        verify(cobrowsingService).purge(expired);
        verify(configRepository).save(eq(config));
        assertThat(config.getLastPurgeDeletedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("purgeExpired: sessão dentro do prazo de retenção é preservada")
    void purgeExpired_sessionWithinRetention_isPreserved() {
        var service = newService();
        var config = CcCobrowseRetentionConfig.builder().retentionDays(1826).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));
        when(sessionRepository.findByStartedAtBeforeAndPurgedAtIsNull(any())).thenReturn(List.of());

        service.purgeExpired();

        verify(cobrowsingService, never()).purge(any());
    }

    @Test
    @DisplayName("purgeExpired: falha (I/O) em um item nunca lança e não impede o expurgo dos demais")
    void purgeExpired_oneItemFails_doesNotStopOthers() {
        var service = newService();
        var config = CcCobrowseRetentionConfig.builder().retentionDays(1826).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        var failing = session(1L, 1900);
        var succeeding = session(2L, 1850);
        when(sessionRepository.findByStartedAtBeforeAndPurgedAtIsNull(any()))
                .thenReturn(List.of(failing, succeeding));
        when(cobrowsingService.purge(failing)).thenThrow(new RuntimeException("falha de I/O simulada"));
        when(cobrowsingService.purge(succeeding)).thenReturn(succeeding);

        var result = service.purgeExpired();

        assertThat(result.purgedCount()).isEqualTo(1);
        verify(cobrowsingService).purge(failing);
        verify(cobrowsingService).purge(succeeding);
        assertThat(config.getLastPurgeDeletedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("purgeExpired: idempotente — rodar duas vezes não reprocessa o que já tem purgedAt")
    void purgeExpired_runTwiceInARow_secondRunFindsNothing() {
        var service = newService();
        var config = CcCobrowseRetentionConfig.builder().retentionDays(1826).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        var expired = session(1L, 1827);
        when(sessionRepository.findByStartedAtBeforeAndPurgedAtIsNull(any()))
                .thenReturn(List.of(expired))
                .thenReturn(List.of());
        when(cobrowsingService.purge(expired)).thenReturn(expired);

        var first = service.purgeExpired();
        var second = service.purgeExpired();

        assertThat(first.purgedCount()).isEqualTo(1);
        assertThat(second.purgedCount()).isZero();
        verify(cobrowsingService, times(1)).purge(expired);
    }

    @Test
    @DisplayName("updateConfig: atualiza retentionDays e updatedBy")
    void updateConfig_updatesFields() {
        var service = newService();
        var config = CcCobrowseRetentionConfig.builder().retentionDays(1826).build();
        when(configRepository.findById("default")).thenReturn(Optional.of(config));

        var view = service.updateConfig(new CobrowseRetentionConfigRequest(365), "admin@example.com");

        assertThat(view.retentionDays()).isEqualTo(365);
        assertThat(config.getUpdatedBy()).isEqualTo("admin@example.com");
    }

    @Test
    @DisplayName("getConfig: usa default (1826 dias / 60 meses) quando nenhuma linha existe ainda")
    void getConfig_noRowYet_usesDefault() {
        var service = newService();
        when(configRepository.findById("default")).thenReturn(Optional.empty());

        var view = service.getConfig();

        assertThat(view.retentionDays()).isEqualTo(1826);
    }
}
