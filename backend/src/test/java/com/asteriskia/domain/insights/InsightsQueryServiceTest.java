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
    @Mock private com.asteriskia.domain.callcenter.recording.CcRecordingRepository ccRecordingRepository;

    private InsightsQueryService service;

    @BeforeEach
    void setUp() {
        service = new InsightsQueryService(audioFileRepository, segmentRepository, insightRepository,
                findingRepository, evaluationRepository, evaluationItemRepository, transferEventRepository,
                ccRecordingRepository);
        lenientEmptyPage();
    }

    private void lenientEmptyPage() {
        lenient().when(audioFileRepository.findAll(any(Specification.class), any(Pageable.class)))
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

    // --- Escopo por BU (2026-08-15, gap do Insights do Call Center) ---
    // Fail-open documentado: gravação sem ccRecordingId, sem CcRecording correspondente, ou cuja
    // fila não tem BU atribuída fica visível a qualquer usuário restrito por BU.

    private CallAudioFile audioFileWithRecording(Long ccRecordingId) {
        return CallAudioFile.builder().id(1L).source("callcenter").ccRecordingId(ccRecordingId).build();
    }

    @Test
    @DisplayName("findAudioFileById: businessUnitIds nulo (ADMIN) nunca consulta CcRecordingRepository")
    void findAudioFileById_unrestricted_neverChecksRecording() {
        when(audioFileRepository.findById(1L)).thenReturn(java.util.Optional.of(audioFileWithRecording(9L)));

        service.findAudioFileById(1L, null);

        verify(ccRecordingRepository, never()).findById(any());
    }

    @Test
    @DisplayName("findAudioFileById: sem ccRecordingId, fail-open mesmo com businessUnitIds restrito")
    void findAudioFileById_noCcRecordingId_failsOpen() {
        when(audioFileRepository.findById(1L)).thenReturn(java.util.Optional.of(audioFileWithRecording(null)));

        var result = service.findAudioFileById(1L, java.util.Set.of(5));

        org.assertj.core.api.Assertions.assertThat(result.getId()).isEqualTo(1L);
        verify(ccRecordingRepository, never()).findById(any());
    }

    @Test
    @DisplayName("findAudioFileById: CcRecording sem BU atribuída, fail-open")
    void findAudioFileById_recordingWithoutBusinessUnit_failsOpen() {
        when(audioFileRepository.findById(1L)).thenReturn(java.util.Optional.of(audioFileWithRecording(9L)));
        when(ccRecordingRepository.findById(9L))
                .thenReturn(java.util.Optional.of(
                        com.asteriskia.domain.callcenter.recording.CcRecording.builder().id(9L).build()));

        var result = service.findAudioFileById(1L, java.util.Set.of(5));

        org.assertj.core.api.Assertions.assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("findAudioFileById: BU da gravação fora do escopo do usuário vira 404")
    void findAudioFileById_wrongBusinessUnit_rejected() {
        when(audioFileRepository.findById(1L)).thenReturn(java.util.Optional.of(audioFileWithRecording(9L)));
        var otherBu = com.asteriskia.domain.masterdata.BusinessUnit.builder().id(7).build();
        when(ccRecordingRepository.findById(9L))
                .thenReturn(java.util.Optional.of(
                        com.asteriskia.domain.callcenter.recording.CcRecording.builder().id(9L).businessUnit(otherBu).build()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.findAudioFileById(1L, java.util.Set.of(5)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("findAudioFileById: BU da gravação dentro do escopo do usuário é permitido")
    void findAudioFileById_matchingBusinessUnit_allowed() {
        when(audioFileRepository.findById(1L)).thenReturn(java.util.Optional.of(audioFileWithRecording(9L)));
        var bu = com.asteriskia.domain.masterdata.BusinessUnit.builder().id(5).build();
        when(ccRecordingRepository.findById(9L))
                .thenReturn(java.util.Optional.of(
                        com.asteriskia.domain.callcenter.recording.CcRecording.builder().id(9L).businessUnit(bu).build()));

        var result = service.findAudioFileById(1L, java.util.Set.of(5));

        org.assertj.core.api.Assertions.assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("detail: registro fora do escopo de BU vira 404 antes de montar a resposta")
    void detail_wrongBusinessUnit_rejected() {
        when(audioFileRepository.findById(1L)).thenReturn(java.util.Optional.of(audioFileWithRecording(9L)));
        var otherBu = com.asteriskia.domain.masterdata.BusinessUnit.builder().id(7).build();
        when(ccRecordingRepository.findById(9L))
                .thenReturn(java.util.Optional.of(
                        com.asteriskia.domain.callcenter.recording.CcRecording.builder().id(9L).businessUnit(otherBu).build()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.detail(1L, false, java.util.Set.of(5)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(segmentRepository, never()).findByAudioFileIdOrderByStartMsAsc(any());
    }
}
