package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import com.asteriskia.domain.callcenter.interaction.CcDisposition;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.interaction.Direction;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallInsightRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

/** Cobre o agrupamento por ANI normalizado do "Perfil do cliente" (Fase 27). */
@ExtendWith(MockitoExtension.class)
class CallCenterCustomerProfileServiceTest {

    @Mock private CcInteractionRepository interactionRepository;
    @Mock private CcChatSessionRepository chatSessionRepository;
    @Mock private CcRecordingRepository recordingRepository;
    @Mock private CallAudioFileRepository audioFileRepository;
    @Mock private CallInsightRepository insightRepository;

    private CallCenterCustomerProfileService service;

    private final LocalDate from = LocalDate.of(2026, 8, 1);
    private final LocalDate to = LocalDate.of(2026, 8, 14);

    @BeforeEach
    void setUp() {
        service = new CallCenterCustomerProfileService(
                interactionRepository, chatSessionRepository, recordingRepository, audioFileRepository, insightRepository);
    }

    private CcInteraction interaction(String ani, LocalDateTime queuedAt, BigDecimal nps, CcDisposition disposition) {
        return CcInteraction.builder()
                .id(1L).ani(ani).direction(Direction.INBOUND).queuedAt(queuedAt).npsScore(nps).disposition(disposition)
                .queue(CcQueue.builder().id(1L).name("5001").displayName("Suporte").build())
                .agent(CcAgent.builder().id(1L).name("Kaio").build())
                .build();
    }

    @Test
    void search_duasVariacoesDoMesmoTelefone_agrupamNoMesmoCliente() {
        CcInteraction comNove = interaction("+5511981234567", LocalDateTime.of(2026, 8, 1, 10, 0), null, null);
        CcInteraction semNove = interaction("11 8123-4567", LocalDateTime.of(2026, 8, 5, 11, 0), null, null);
        when(interactionRepository.findByQueuedAtBetween(any(), any())).thenReturn(List.of(comNove, semNove));
        when(chatSessionRepository.findByStartedAtBetween(any(), any())).thenReturn(List.of());

        Page<CustomerProfileSummaryRow> page = service.search(from, to, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).totalChamadas()).isEqualTo(2);
        assertThat(page.getContent().get(0).normalizedId()).isEqualTo("11981234567");
    }

    @Test
    void search_displayContact_usaOMaisRecentePorTimestampNaoAOrdemDaLista() {
        // Mesmo cliente (ANI normaliza igual nos dois formatos), interação mais recente listada
        // ANTES da mais antiga — prova que o serviço não pode assumir "último elemento da lista"
        // como o mais recente, já que o repositório não garante ordem.
        CcInteraction maisRecente = interaction("11 98123-4567", LocalDateTime.of(2026, 8, 10, 9, 0), null, null);
        CcInteraction maisAntiga = interaction("+55 11 8123-4567", LocalDateTime.of(2026, 8, 1, 9, 0), null, null);
        when(interactionRepository.findByQueuedAtBetween(any(), any())).thenReturn(List.of(maisRecente, maisAntiga));
        when(chatSessionRepository.findByStartedAtBetween(any(), any())).thenReturn(List.of());

        Page<CustomerProfileSummaryRow> page = service.search(from, to, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).displayContact()).isEqualTo("11 98123-4567");
    }

    @Test
    void search_semAniNemCustomerRef_naoQuebra() {
        when(interactionRepository.findByQueuedAtBetween(any(), any())).thenReturn(List.of());
        when(chatSessionRepository.findByStartedAtBetween(any(), any())).thenReturn(List.of());

        Page<CustomerProfileSummaryRow> page = service.search(from, to, PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void detail_contatoInvalido_lanca400() {
        assertThatThrownBy(() -> service.detail("---", from, to))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Contato inválido");
    }

    @Test
    void detail_semNenhumContatoNoPeriodo_lanca404() {
        when(interactionRepository.findByQueuedAtBetween(any(), any())).thenReturn(List.of());
        when(chatSessionRepository.findByStartedAtBetween(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.detail("11981234567", from, to))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Nenhum contato encontrado");
    }

    @Test
    void detail_calculaTopAssuntoENpsMedio() {
        CcDisposition duvida = CcDisposition.builder().id(1L).code("duvida").label("Dúvida sobre fatura").active(true).build();
        CcInteraction i1 = interaction("11981234567", LocalDateTime.of(2026, 8, 1, 10, 0), new BigDecimal("9"), duvida);
        CcInteraction i2 = interaction("11981234567", LocalDateTime.of(2026, 8, 5, 11, 0), new BigDecimal("7"), duvida);
        when(interactionRepository.findByQueuedAtBetween(any(), any())).thenReturn(List.of(i1, i2));
        when(chatSessionRepository.findByStartedAtBetween(any(), any())).thenReturn(List.of());
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());

        CustomerProfileDetail detail = service.detail("11981234567", from, to);

        assertThat(detail.totalChamadas()).isEqualTo(2);
        assertThat(detail.npsMedio()).isEqualByComparingTo("8.00");
        assertThat(detail.topAssuntos()).hasSize(1);
        assertThat(detail.topAssuntos().get(0).assunto()).isEqualTo("Dúvida sobre fatura");
        assertThat(detail.topAssuntos().get(0).total()).isEqualTo(2L);
    }
}
