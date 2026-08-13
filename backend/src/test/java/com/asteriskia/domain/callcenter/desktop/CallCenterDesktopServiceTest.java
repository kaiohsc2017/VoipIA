package com.asteriskia.domain.callcenter.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcPauseReason;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.interaction.Direction;
import com.asteriskia.domain.callcenter.recording.CcRecording;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.asteriskia.domain.insights.CallAudioFile;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallTranscriptSegment;
import com.asteriskia.domain.insights.CallTranscriptSegmentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre as três regras de negócio da Fase 22 (painel pessoal do agente): cálculo do resumo do
 * dia, a regra fechada D21 do histórico (somente leitura, nunca enfileira processamento) e o
 * recorte de pausas dentro da janela "hoje até agora".
 */
@ExtendWith(MockitoExtension.class)
class CallCenterDesktopServiceTest {

    @Mock
    private CallCenterAgentStateService agentStateService;
    @Mock
    private CcInteractionRepository interactionRepository;
    @Mock
    private CcAgentStateRepository agentStateRepository;
    @Mock
    private CcRecordingRepository recordingRepository;
    @Mock
    private CallAudioFileRepository audioFileRepository;
    @Mock
    private CallTranscriptSegmentRepository transcriptSegmentRepository;

    private CallCenterDesktopService service;
    private CcAgent agent;

    @BeforeEach
    void setUp() {
        service = new CallCenterDesktopService(agentStateService, interactionRepository,
                agentStateRepository, recordingRepository, audioFileRepository, transcriptSegmentRepository);
        agent = CcAgent.builder().id(7L).name("Kaio").build();
        when(agentStateService.currentAgent()).thenReturn(agent);
    }

    private CcInteraction interaction(Long id, LocalDateTime queuedAt, LocalDateTime answeredAt,
            LocalDateTime endedAt) {
        return CcInteraction.builder()
                .id(id)
                .agent(agent)
                .direction(Direction.INBOUND)
                .channelUniqueId("chan-" + id)
                .queue(CcQueue.builder().id(1L).name("Suporte").build())
                .queuedAt(queuedAt)
                .answeredAt(answeredAt)
                .endedAt(endedAt)
                .build();
    }

    @Test
    @DisplayName("resumo calcula TMA só com chamadas atendidas e encerradas")
    void resumo_calculaTmaSoComChamadasEncerradas() {
        var now = LocalDateTime.now();
        var answeredAndEnded = interaction(1L, now.minusHours(1), now.minusHours(1).plusSeconds(10),
                now.minusHours(1).plusSeconds(70));
        var answeredNotEnded = interaction(2L, now.minusMinutes(5), now.minusMinutes(4), null);
        var neverAnswered = interaction(3L, now.minusMinutes(2), null, null);

        when(interactionRepository.findByAgentIdAndQueuedAtBetween(eq(7L), any(), any()))
                .thenReturn(List.of(answeredAndEnded, answeredNotEnded, neverAnswered));
        when(agentStateRepository.findOverlapping(eq(7L), any(), any())).thenReturn(List.of());

        var resumo = service.resumo();

        assertThat(resumo.callsAnsweredToday()).isEqualTo(2);
        assertThat(resumo.avgTalkSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("resumo soma tempo logado e tempo em pausa a partir dos estados do dia")
    void resumo_somaTempoLogadoEPausa() {
        var start = LocalDate.now().atStartOfDay();
        when(interactionRepository.findByAgentIdAndQueuedAtBetween(eq(7L), any(), any())).thenReturn(List.of());
        when(agentStateRepository.findOverlapping(eq(7L), any(), any())).thenReturn(List.of(
                CcAgentState.builder().agent(agent).state(AgentState.DISPONIVEL)
                        .startedAt(start).endedAt(start.plusHours(2)).build(),
                CcAgentState.builder().agent(agent).state(AgentState.PAUSA)
                        .startedAt(start.plusHours(2)).endedAt(start.plusHours(2).plusMinutes(15)).build(),
                CcAgentState.builder().agent(agent).state(AgentState.OFFLINE)
                        .startedAt(start.plusHours(2).plusMinutes(15)).endedAt(null).build()));

        var resumo = service.resumo();

        assertThat(resumo.pauseSeconds()).isEqualTo(15 * 60);
        assertThat(resumo.loggedSeconds()).isEqualTo(2 * 3600 + 15 * 60);
    }

    @Test
    @DisplayName("D21: chamada gravada mas não processada aparece EM_PROCESSAMENTO e nada é enfileirado")
    void historico_gravacaoNaoProcessada_naoEnfileiraNada() {
        var call = interaction(10L, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now().plusMinutes(3));
        var recording = CcRecording.builder().id(99L).interactionId(10L).build();
        var pendingAudioFile = CallAudioFile.builder().id(500L).status("pending").build();

        when(interactionRepository.findByAgentIdAndQueuedAtBetween(eq(7L), any(), any())).thenReturn(List.of(call));
        when(recordingRepository.findByInteractionId(10L)).thenReturn(Optional.of(recording));
        when(audioFileRepository.findByCcRecordingId(99L)).thenReturn(Optional.of(pendingAudioFile));

        var historico = service.historico();

        assertThat(historico).hasSize(1);
        assertThat(historico.get(0).transcriptionStatus()).isEqualTo("EM_PROCESSAMENTO");
        assertThat(historico.get(0).transcript()).isNull();
        assertThat(historico.get(0).recordingUrl()).isEqualTo("/callcenter/recordings/99/audio");

        // Asserção explícita sobre a "fila" (status=pending do CallAudioFile): nunca é
        // mutada por este serviço — ele só lê. Nenhuma chamada de escrita em nenhum dos
        // dois repositórios de Insights, e o serviço nem depende de InsightsIngestionService.
        verify(audioFileRepository, never()).save(any());
        verifyNoMoreInteractions(transcriptSegmentRepository);
    }

    @Test
    @DisplayName("D21: chamada sem gravação nenhuma aparece SEM_GRAVACAO")
    void historico_semGravacao() {
        var call = interaction(11L, LocalDateTime.now(), null, null);
        when(interactionRepository.findByAgentIdAndQueuedAtBetween(eq(7L), any(), any())).thenReturn(List.of(call));
        when(recordingRepository.findByInteractionId(11L)).thenReturn(Optional.empty());

        var historico = service.historico();

        assertThat(historico.get(0).transcriptionStatus()).isEqualTo("SEM_GRAVACAO");
        assertThat(historico.get(0).recordingUrl()).isNull();
    }

    @Test
    @DisplayName("chamada com transcrição pronta junta os segmentos em ordem")
    void historico_transcricaoPronta_juntaSegmentos() {
        var call = interaction(12L, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now().plusMinutes(2));
        var recording = CcRecording.builder().id(77L).interactionId(12L).build();
        var doneAudioFile = CallAudioFile.builder().id(600L).status("done").build();

        when(interactionRepository.findByAgentIdAndQueuedAtBetween(eq(7L), any(), any())).thenReturn(List.of(call));
        when(recordingRepository.findByInteractionId(12L)).thenReturn(Optional.of(recording));
        when(audioFileRepository.findByCcRecordingId(77L)).thenReturn(Optional.of(doneAudioFile));
        when(transcriptSegmentRepository.findByAudioFileIdOrderByStartMsAsc(600L)).thenReturn(List.of(
                CallTranscriptSegment.builder().speaker("cliente").text("Olá").startMs(0).endMs(500).build(),
                CallTranscriptSegment.builder().speaker("agente").text("Bom dia").startMs(600).endMs(900).build()));

        var historico = service.historico();

        assertThat(historico.get(0).transcriptionStatus()).isEqualTo("DISPONIVEL");
        assertThat(historico.get(0).transcript()).isEqualTo("cliente: Olá\nagente: Bom dia");
    }

    @Test
    @DisplayName("pausas recorta duração dentro da janela e mantém pausa em curso sem endedAt")
    void pausas_recortaDuracaoEMantemPausaEmCurso() {
        var start = LocalDate.now().atStartOfDay();
        var reason = CcPauseReason.builder().id(1L).label("Almoço").build();
        when(agentStateRepository.findOverlapping(eq(7L), any(), any())).thenReturn(List.of(
                CcAgentState.builder().agent(agent).state(AgentState.PAUSA).pauseReason(reason)
                        .startedAt(start.plusHours(1)).endedAt(start.plusHours(1).plusMinutes(10)).build(),
                CcAgentState.builder().agent(agent).state(AgentState.PAUSA).pauseReason(reason)
                        .startedAt(start.plusHours(3)).endedAt(null).build(),
                CcAgentState.builder().agent(agent).state(AgentState.DISPONIVEL)
                        .startedAt(start.plusHours(2)).endedAt(start.plusHours(3)).build()));

        var pausas = service.pausas();

        assertThat(pausas).hasSize(2);
        assertThat(pausas.get(0).durationSeconds()).isEqualTo(600);
        assertThat(pausas.get(0).endedAt()).isNotNull();
        assertThat(pausas.get(1).endedAt()).isNull();
        assertThat(pausas.get(1).reasonLabel()).isEqualTo("Almoço");
    }

    @Test
    @DisplayName("resumo/histórico/pausas nunca resolvem agente por id vindo de fora — sempre currentAgent()")
    void nuncaAceitaAgentIdExterno() {
        when(interactionRepository.findByAgentIdAndQueuedAtBetween(eq(7L), any(), any())).thenReturn(List.of());
        when(agentStateRepository.findOverlapping(eq(7L), any(), any())).thenReturn(List.of());

        service.resumo();
        service.historico();
        service.pausas();

        // As três chamadas resolveram o mesmo agente (id=7) exclusivamente via
        // currentAgent() — nenhum método deste serviço tem parâmetro de agentId.
        verify(interactionRepository, times(2))
                .findByAgentIdAndQueuedAtBetween(eq(7L), any(), any());
    }
}
