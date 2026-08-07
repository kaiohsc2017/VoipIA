package com.asteriskia.domain.callcenter.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.insights.InsightsIngestionService;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CallCenterRecordingServiceTest — ingestão idempotente, resolução de config de gravação por
 * fila e defesa de path traversal no streaming (Fase 3); registro no pipeline de Insights e
 * correlação com a interação formal (Fase 8).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallCenterRecordingServiceTest {

    @Mock private CcRecordingRepository recordingRepository;
    @Mock private CcQueueRepository queueRepository;
    @Mock private CcInteractionRepository interactionRepository;
    @Mock private InsightsIngestionService insightsIngestionService;

    private CallCenterRecordingService newService() throws Exception {
        var service = new CallCenterRecordingService(
                recordingRepository, queueRepository, interactionRepository, insightsIngestionService);
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

    @Test
    @DisplayName("ingest correlaciona a interação por channelUniqueId e registra no Insights com agente/fila")
    void ingest_withMatchingInteraction_registersInsightsWithAgentAndQueue() throws Exception {
        var service = newService();
        var bu = BusinessUnit.builder().id(7).build();
        var queue = CcQueue.builder().id(1L).name("5001").displayName("Suporte N1").businessUnit(bu).build();
        var agent = CcAgent.builder().id(3L).name("Fulano de Tal").build();
        var interaction = CcInteraction.builder()
                .id(42L)
                .agent(agent)
                .queue(queue)
                .ani("11999998888")
                .channelUniqueId("1700000000.123")
                .build();
        when(recordingRepository.findByChannelUniqueId("1700000000.123")).thenReturn(Optional.empty());
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(queue));
        when(interactionRepository.findByChannelUniqueId("1700000000.123")).thenReturn(Optional.of(interaction));
        when(recordingRepository.save(org.mockito.ArgumentMatchers.any(CcRecording.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = service.ingest(
                "1700000000.123", "5001", "/opt/telecom/gravacao/2026/08/06/1700000000.123.wav", true);

        assertThat(result.getInteractionId()).isEqualTo(42L);
        ArgumentCaptor<String> wavPathCaptor = ArgumentCaptor.forClass(String.class);
        verify(insightsIngestionService)
                .registerCallCenterRecording(
                        org.mockito.ArgumentMatchers.eq("cc-1700000000.123"),
                        wavPathCaptor.capture(),
                        org.mockito.ArgumentMatchers.eq("Fulano de Tal"),
                        org.mockito.ArgumentMatchers.eq("Suporte N1"),
                        org.mockito.ArgumentMatchers.eq("11999998888"),
                        org.mockito.ArgumentMatchers.eq(result.getId()));
        // wavPath NUNCA é o filePath bruto do dialplan — é resolvido (nome-base + subpasta
        // derivada de startedAt) dentro de recordingBasePath, mesma defesa de path
        // traversal de resolveAudioFile (achado real do code-reviewer, Fase 8).
        assertThat(wavPathCaptor.getValue())
                .startsWith(new java.io.File("/opt/telecom/gravacao").getCanonicalPath())
                .endsWith("1700000000.123.wav");
    }

    @Test
    @DisplayName("ingest sem interação correlacionada ainda registra no Insights, sem agente")
    void ingest_withoutMatchingInteraction_registersInsightsWithoutAgent() throws Exception {
        var service = newService();
        when(recordingRepository.findByChannelUniqueId("1700000000.999")).thenReturn(Optional.empty());
        when(queueRepository.findByName("5001")).thenReturn(Optional.empty());
        when(interactionRepository.findByChannelUniqueId("1700000000.999")).thenReturn(Optional.empty());
        when(recordingRepository.save(org.mockito.ArgumentMatchers.any(CcRecording.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = service.ingest("1700000000.999", "5001", "/opt/telecom/gravacao/x.wav", false);

        ArgumentCaptor<String> wavPathCaptor = ArgumentCaptor.forClass(String.class);
        verify(insightsIngestionService)
                .registerCallCenterRecording(
                        org.mockito.ArgumentMatchers.eq("cc-1700000000.999"),
                        wavPathCaptor.capture(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(result.getId()));
        assertThat(wavPathCaptor.getValue())
                .startsWith(new java.io.File("/opt/telecom/gravacao").getCanonicalPath())
                .endsWith("x.wav");
    }

    @Test
    @DisplayName("ingest com filePath de path traversal não registra no Insights (registerInsights nunca chamado)")
    void ingest_maliciousFilePath_doesNotRegisterInsights() throws Exception {
        var service = newService();
        when(recordingRepository.findByChannelUniqueId("1700000000.7")).thenReturn(Optional.empty());
        when(queueRepository.findByName("5001")).thenReturn(Optional.empty());
        when(interactionRepository.findByChannelUniqueId("1700000000.7")).thenReturn(Optional.empty());
        when(recordingRepository.save(org.mockito.ArgumentMatchers.any(CcRecording.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.ingest("1700000000.7", "5001", "../../../../etc/passwd", false);

        // resolveAudioFile sempre reconstrói dentro de recordingBasePath a partir do
        // nome-base — "../../../../etc/passwd" vira só "passwd", um arquivo que não existe
        // dentro de recordingBasePath nesta suíte (sem I/O real), então nunca deveria
        // expor o caminho malicioso ao serviço de Insights.
        verify(insightsIngestionService, org.mockito.Mockito.never())
                .registerCallCenterRecording(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.contains(".."),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("ingest não falha se o registro no Insights lançar exceção")
    void ingest_insightsRegistrationThrows_doesNotPropagate() throws Exception {
        var service = newService();
        when(recordingRepository.findByChannelUniqueId("1700000000.1")).thenReturn(Optional.empty());
        when(queueRepository.findByName("5001")).thenReturn(Optional.empty());
        when(interactionRepository.findByChannelUniqueId("1700000000.1")).thenReturn(Optional.empty());
        when(recordingRepository.save(org.mockito.ArgumentMatchers.any(CcRecording.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("backend indisponível"))
                .when(insightsIngestionService)
                .registerCallCenterRecording(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        var result = service.ingest("1700000000.1", "5001", "/opt/telecom/gravacao/x.wav", false);

        assertThat(result).isNotNull();
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
