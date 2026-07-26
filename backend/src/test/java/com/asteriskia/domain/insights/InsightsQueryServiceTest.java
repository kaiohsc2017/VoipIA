package com.asteriskia.domain.insights;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * InsightsQueryServiceTest — cobre o gate ADMIN do filtro targetSwitchCallId
 * (Task 5 do plano insights-chamadas-campos-xml): mesmo que o filtro chegue
 * preenchido (ex: parâmetro forjado direto na URL, contornando o frontend),
 * a busca por audioFileIds na tabela de transferências só pode acontecer
 * quando isAdmin=true.
 */
@ExtendWith(MockitoExtension.class)
class InsightsQueryServiceTest {

    @Mock private CallAudioFileRepository audioFileRepository;
    @Mock private CallTranscriptSegmentRepository segmentRepository;
    @Mock private CallInsightRepository insightRepository;
    @Mock private CallInsightFindingRepository findingRepository;
    @Mock private CallEvaluationRepository evaluationRepository;
    @Mock private CallEvaluationItemRepository evaluationItemRepository;
    @Mock private CallTransferEventRepository transferEventRepository;

    private InsightsQueryService service;

    @BeforeEach
    void setUp() {
        service = new InsightsQueryService(audioFileRepository, segmentRepository, insightRepository,
                findingRepository, evaluationRepository, evaluationItemRepository, transferEventRepository);
        lenientEmptyPage();
    }

    private void lenientEmptyPage() {
        when(audioFileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());
    }

    // InsightsFilter tem 28 campos posicionais — construído explicitamente em blocos de
    // 7 (id..criticidade / findingType..isFailed / extension..transferTargetAgentName /
    // agentLoginId..targetSwitchCallId) pra facilitar contagem e revisão.
    private InsightsFilter blankFilter() {
        return new InsightsFilter(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private InsightsFilter filterWithTargetSwitchCallId() {
        InsightsFilter blank = blankFilter();
        return new InsightsFilter(
                blank.id(), blank.dateFrom(), blank.dateTo(), blank.text(), blank.phrase(), blank.toneCliente(),
                blank.toneAtendente(), blank.categoria(), blank.criticidade(), blank.findingType(), blank.agentName(),
                blank.direction(), blank.skill(), blank.durationMin(), blank.durationMax(), blank.notaMin(),
                blank.notaMax(), blank.isFailed(), blank.extension(), blank.disconnectedBy(),
                blank.hasHold(), blank.wrapupTimeMin(), blank.wrapupTimeMax(), blank.transferTargetExtension(),
                blank.transferTargetAgentName(), blank.agentLoginId(), blank.telCliente(), "SW-FORJADO");
    }

    @Test
    @DisplayName("não-ADMIN: filtro targetSwitchCallId é ignorado, repositório nunca é consultado")
    void search_nonAdmin_neverResolvesTargetSwitchCallId() {
        Pageable pageable = PageRequest.of(0, 20);

        service.search(filterWithTargetSwitchCallId(), pageable, false);

        verify(transferEventRepository, never()).findAudioFileIdsByTargetSwitchCallId(any());
    }

    @Test
    @DisplayName("ADMIN: filtro targetSwitchCallId é resolvido normalmente")
    void search_admin_resolvesTargetSwitchCallId() {
        Pageable pageable = PageRequest.of(0, 20);
        when(transferEventRepository.findAudioFileIdsByTargetSwitchCallId("SW-FORJADO")).thenReturn(List.of(42L));

        service.search(filterWithTargetSwitchCallId(), pageable, true);

        verify(transferEventRepository).findAudioFileIdsByTargetSwitchCallId("SW-FORJADO");
    }

    @Test
    @DisplayName("filtros de transferência (ramal/atendente destino) não exigem ADMIN")
    void search_transferTargetFilters_notAdminGated() {
        Pageable pageable = PageRequest.of(0, 20);
        InsightsFilter blank = blankFilter();
        InsightsFilter filter = new InsightsFilter(
                blank.id(), blank.dateFrom(), blank.dateTo(), blank.text(), blank.phrase(), blank.toneCliente(),
                blank.toneAtendente(), blank.categoria(), blank.criticidade(), blank.findingType(), blank.agentName(),
                blank.direction(), blank.skill(), blank.durationMin(), blank.durationMax(), blank.notaMin(),
                blank.notaMax(), blank.isFailed(), blank.extension(), blank.disconnectedBy(),
                blank.hasHold(), blank.wrapupTimeMin(), blank.wrapupTimeMax(), "4108", "Diego",
                blank.agentLoginId(), blank.telCliente(), blank.targetSwitchCallId());
        when(transferEventRepository.findAudioFileIdsByTargetExtension("4108")).thenReturn(List.of(1L));
        when(transferEventRepository.findAudioFileIdsByTargetAgentName("Diego")).thenReturn(List.of(1L));

        service.search(filter, pageable, false);

        verify(transferEventRepository).findAudioFileIdsByTargetExtension("4108");
        verify(transferEventRepository).findAudioFileIdsByTargetAgentName("Diego");
    }

    @Test
    @DisplayName("filtro agentLoginId não exige ADMIN e é repassado ao Specification")
    void search_agentLoginIdFilter_worksForAnyUser() {
        Pageable pageable = PageRequest.of(0, 20);
        InsightsFilter blank = blankFilter();
        InsightsFilter filter = new InsightsFilter(
                blank.id(), blank.dateFrom(), blank.dateTo(), blank.text(), blank.phrase(), blank.toneCliente(),
                blank.toneAtendente(), blank.categoria(), blank.criticidade(), blank.findingType(), blank.agentName(),
                blank.direction(), blank.skill(), blank.durationMin(), blank.durationMax(), blank.notaMin(),
                blank.notaMax(), blank.isFailed(), blank.extension(), blank.disconnectedBy(),
                blank.hasHold(), blank.wrapupTimeMin(), blank.wrapupTimeMax(), blank.transferTargetExtension(),
                blank.transferTargetAgentName(), "39773", blank.telCliente(), blank.targetSwitchCallId());

        service.search(filter, pageable, false);

        verify(audioFileRepository).findAll(any(Specification.class), eq(pageable));
    }
}
