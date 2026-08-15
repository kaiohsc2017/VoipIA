package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.interaction.CcDisposition;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre a regra de negócio da rechamada (sub-fase 9c.4): rechamada é contada por ANI normalizado
 * em QUALQUER fila da operação, não só na mesma fila — e respeita a janela de 24h/7d.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterRecallServiceTest {

    @Mock
    private CcInteractionRepository interactionRepository;

    private CallCenterRecallService service;

    private CcQueue queueA;
    private CcQueue queueB;
    private final LocalDate day = LocalDate.of(2026, 8, 14);

    @BeforeEach
    void setUp() {
        service = new CallCenterRecallService(interactionRepository);
        queueA = CcQueue.builder().id(1L).name("5001").build();
        queueB = CcQueue.builder().id(2L).name("5002").build();
    }

    private CcInteraction call(CcQueue queue, String ani, LocalDateTime queuedAt) {
        return CcInteraction.builder().queue(queue).ani(ani).queuedAt(queuedAt).build();
    }

    @Test
    @DisplayName("rechamada 24h conta contato que já ligou pra OUTRA fila dentro da janela")
    void summarize_recall24h_countsContactAcrossDifferentQueues() {
        LocalDateTime t = day.atTime(10, 0);
        // mesmo cliente ligou pra fila B 2h antes, depois pra fila A (cohort) — é rechamada
        CcInteraction priorOnQueueB = call(queueB, "11987654321", t.minusHours(2));
        CcInteraction cohortCall = call(queueA, "11987654321", t);
        // cliente novo, sem contato prévio — não é rechamada
        CcInteraction freshCall = call(queueA, "11900000000", t);

        when(interactionRepository.findByQueuedAtBetween(any(), any()))
                .thenReturn(List.of(priorOnQueueB, cohortCall, freshCall));

        RecallAndDispositionSummary summary = service.summarize(1L, day, day);

        assertThat(summary.totalReceived()).isEqualTo(2);
        assertThat(summary.recall24hCount()).isEqualTo(1);
        assertThat(summary.recall24hRatePct()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("contato fora da janela de 24h mas dentro de 7d só conta pra rechamada de 7d")
    void summarize_recallWindows_areIndependent() {
        LocalDateTime t = day.atTime(10, 0);
        CcInteraction priorThreeDaysAgo = call(queueA, "11987654321", t.minusDays(3));
        CcInteraction cohortCall = call(queueA, "11987654321", t);

        when(interactionRepository.findByQueuedAtBetween(any(), any()))
                .thenReturn(List.of(priorThreeDaysAgo, cohortCall));

        RecallAndDispositionSummary summary = service.summarize(1L, day, day);

        assertThat(summary.recall24hCount()).isZero();
        assertThat(summary.recall7dCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("top tabulações soma as disposições da fila no período, maior contagem primeiro")
    void summarize_topDispositions_sortedByCountDesc() {
        LocalDateTime t = day.atTime(10, 0);
        CcDisposition resolvido = CcDisposition.builder().id(1L).label("Resolvido").build();
        CcDisposition duvida = CcDisposition.builder().id(2L).label("Dúvida").build();

        CcInteraction c1 = call(queueA, "111", t);
        c1.setDisposition(resolvido);
        CcInteraction c2 = call(queueA, "222", t);
        c2.setDisposition(resolvido);
        CcInteraction c3 = call(queueA, "333", t);
        c3.setDisposition(duvida);

        when(interactionRepository.findByQueuedAtBetween(any(), any())).thenReturn(List.of(c1, c2, c3));

        RecallAndDispositionSummary summary = service.summarize(1L, day, day);

        assertThat(summary.topDispositions()).hasSize(2);
        assertThat(summary.topDispositions().get(0).label()).isEqualTo("Resolvido");
        assertThat(summary.topDispositions().get(0).count()).isEqualTo(2);
    }
}
