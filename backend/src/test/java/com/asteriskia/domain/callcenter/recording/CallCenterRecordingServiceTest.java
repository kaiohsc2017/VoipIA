package com.asteriskia.domain.callcenter.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.masterdata.BusinessUnit;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CallCenterRecordingServiceTest — ingestão idempotente, resolução de config de gravação por
 * fila e defesa de path traversal no streaming (Fase 3).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterRecordingServiceTest {

    @Mock private CcRecordingRepository recordingRepository;
    @Mock private CcQueueRepository queueRepository;

    private CallCenterRecordingService newService() throws Exception {
        var service = new CallCenterRecordingService(recordingRepository, queueRepository);
        setRecordingBasePath(service, "/opt/telecom/gravacao");
        return service;
    }

    private static void setRecordingBasePath(CallCenterRecordingService service, String path) throws Exception {
        Field field = CallCenterRecordingService.class.getDeclaredField("recordingBasePath");
        field.setAccessible(true);
        field.set(service, path);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("queueRecordingConfigText: fila com gravação desabilitada retorna record=false")
    void queueRecordingConfigText_recordingDisabled_returnsFalse() throws Exception {
        var service = newService();
        var queue = CcQueue.builder().name("5001").recordingEnabled(false).build();
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(queue));

        assertThat(service.queueRecordingConfigText("5001")).isEqualTo("record=false");
    }

    @Test
    @DisplayName("queueRecordingConfigText: fila com aviso configurado inclui o campo consent")
    void queueRecordingConfigText_withConsentPath_includesConsent() throws Exception {
        var service = newService();
        var queue = CcQueue.builder()
                .name("5001")
                .recordingEnabled(true)
                .consentMessagePath("/opt/telecom/gravacao/avisos/consentimento.wav")
                .build();
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(queue));

        assertThat(service.queueRecordingConfigText("5001"))
                .isEqualTo("record=true;consent=/opt/telecom/gravacao/avisos/consentimento.wav");
    }

    @Test
    @DisplayName("queueRecordingConfigText: fila inexistente cai em record=true (fail-open)")
    void queueRecordingConfigText_queueNotFound_defaultsToTrue() throws Exception {
        var service = newService();
        when(queueRepository.findByName("5099")).thenReturn(Optional.empty());

        assertThat(service.queueRecordingConfigText("5099")).isEqualTo("record=true");
    }

    @Test
    @DisplayName("ingest grava CcRecording resolvendo fila/BU a partir da extensão")
    void ingest_newChannel_savesRecordingWithResolvedQueue() throws Exception {
        var service = newService();
        var bu = BusinessUnit.builder().id(7).build();
        var queue = CcQueue.builder().id(1L).name("5001").businessUnit(bu).build();
        when(recordingRepository.findByChannelUniqueId("1700000000.123")).thenReturn(Optional.empty());
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(queue));
        when(recordingRepository.save(org.mockito.ArgumentMatchers.any(CcRecording.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = service.ingest("1700000000.123", "5001", "/opt/telecom/gravacao/2026/08/06/1700000000.123.wav", true);

        assertThat(result.getQueue()).isEqualTo(queue);
        assertThat(result.getQueueExtension()).isEqualTo("5001");
        assertThat(result.getBusinessUnit()).isEqualTo(bu);
        assertThat(result.getConsentPlayed()).isTrue();
        // UNIQUEID "epoch.sequencial" — o epoch é usado como startedAt (ver javadoc do service).
        assertThat(result.getStartedAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond())
                .isEqualTo(1700000000L);
    }

    @Test
    @DisplayName("ingest é idempotente por channelUniqueId — retransmissão não duplica")
    void ingest_duplicateChannelUniqueId_returnsExistingWithoutSaving() throws Exception {
        var service = newService();
        var existing = CcRecording.builder().id(99L).channelUniqueId("1700000000.123").build();
        when(recordingRepository.findByChannelUniqueId("1700000000.123")).thenReturn(Optional.of(existing));

        var result = service.ingest("1700000000.123", "5001", "/path/file.wav", false);

        assertThat(result).isEqualTo(existing);
        verify(recordingRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("findByIdInScope: usuário restrito a outra BU não enxerga a gravação (retorna vazio)")
    void findByIdInScope_outOfBusinessUnitScope_returnsEmpty() throws Exception {
        var service = newService();
        restrictToBusinessUnits(1);
        var bu2 = BusinessUnit.builder().id(2).build();
        var recording = CcRecording.builder().id(5L).businessUnit(bu2).build();
        when(recordingRepository.findById(5L)).thenReturn(Optional.of(recording));

        assertThat(service.findByIdInScope(5L)).isEmpty();
    }

    @Test
    @DisplayName("findByIdInScope: usuário restrito à mesma BU enxerga a gravação")
    void findByIdInScope_sameBusinessUnit_returnsRecording() throws Exception {
        var service = newService();
        restrictToBusinessUnits(1);
        var bu1 = BusinessUnit.builder().id(1).build();
        var recording = CcRecording.builder().id(5L).businessUnit(bu1).build();
        when(recordingRepository.findById(5L)).thenReturn(Optional.of(recording));

        assertThat(service.findByIdInScope(5L)).contains(recording);
    }

    @Test
    @DisplayName("resolveAudioFile: ignora diretórios de um filePath malicioso (path traversal)")
    void resolveAudioFile_maliciousFilePath_stripsDirectoryTraversal() throws Exception {
        var service = newService();
        var recording = CcRecording.builder()
                .filePath("../../../../etc/passwd")
                .startedAt(LocalDateTime.of(2026, 8, 6, 10, 0))
                .build();

        var resolved = service.resolveAudioFile(recording);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("passwd");
        assertThat(resolved.getPath()).startsWith(new java.io.File("/opt/telecom/gravacao").getCanonicalPath());
        assertThat(resolved.getPath()).doesNotContain("..");
    }

    private void restrictToBusinessUnits(int... buIds) {
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        for (int id : buIds) {
            authorities.add(new SimpleGrantedAuthority("BU_" + id));
        }
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("user", null, authorities));
    }
}
