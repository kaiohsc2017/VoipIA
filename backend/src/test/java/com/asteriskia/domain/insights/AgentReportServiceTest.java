package com.asteriskia.domain.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre o isolamento por {@code source} (V55) introduzido na Fase 8 do Call Center —
 * o mesmo agentName pode existir tanto em chamadas Verint quanto em Call Center, e um
 * relatório de uma origem nunca pode vazar dado ou identidade (404, não 403) para a
 * outra, mesmo que o solicitante seja ADMIN e o id exista no banco.
 */
@ExtendWith(MockitoExtension.class)
class AgentReportServiceTest {

    @Mock
    private AgentPerformanceReportRepository reportRepository;
    @Mock
    private AgentEvolutionSnapshotRepository snapshotRepository;
    @Mock
    private AgentReportAggregationService aggregationService;

    private AgentReportService service;

    @BeforeEach
    void setUp() {
        service = new AgentReportService(reportRepository, snapshotRepository, aggregationService, new ObjectMapper());
    }

    private AgentPerformanceReport reportOf(Long id, String source, String requestedBy) {
        return AgentPerformanceReport.builder()
                .id(id)
                .agentName("Joao")
                .source(source)
                .dateFrom(LocalDate.of(2026, 8, 1))
                .dateTo(LocalDate.of(2026, 8, 7))
                .requestedBy(requestedBy)
                .status("done")
                .requestedAt(OffsetDateTime.now())
                .build();
    }

    @Test
    @DisplayName("getById não retorna relatório de outra origem, mesmo pertencendo ao solicitante")
    void getById_reportFromOtherSource_returnsEmpty() {
        AgentPerformanceReport verintReport = reportOf(1L, "verint", "supervisor1");
        when(reportRepository.findById(1L)).thenReturn(Optional.of(verintReport));

        Optional<AgentReportDto> result = service.getById(1L, "callcenter", "supervisor1", false);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getById não retorna relatório de outra origem mesmo para ADMIN")
    void getById_reportFromOtherSource_adminAlsoBlocked() {
        AgentPerformanceReport verintReport = reportOf(1L, "verint", "supervisor1");
        when(reportRepository.findById(1L)).thenReturn(Optional.of(verintReport));

        Optional<AgentReportDto> result = service.getById(1L, "callcenter", "qualquer-admin", true);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getById retorna o relatório quando a origem bate e o solicitante é o dono")
    void getById_sameSourceAndOwner_returnsReport() {
        AgentPerformanceReport callcenterReport = reportOf(2L, "callcenter", "supervisor1");
        when(reportRepository.findById(2L)).thenReturn(Optional.of(callcenterReport));

        Optional<AgentReportDto> result = service.getById(2L, "callcenter", "supervisor1", false);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(2L);
    }

    @Test
    @DisplayName("getById não retorna relatório de outro solicitante na mesma origem")
    void getById_sameSourceDifferentOwner_returnsEmpty() {
        AgentPerformanceReport callcenterReport = reportOf(3L, "callcenter", "supervisor1");
        when(reportRepository.findById(3L)).thenReturn(Optional.of(callcenterReport));

        Optional<AgentReportDto> result = service.getById(3L, "callcenter", "supervisor2", false);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("list consulta o repositório sempre com a origem do chamador, nunca todas juntas")
    void list_passesSourceThrough() {
        when(reportRepository.findByRequestedByAndSourceOrderByRequestedAtDesc(
                anyString(), anyString(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.list("supervisor1", "callcenter", false, org.springframework.data.domain.PageRequest.of(0, 20));

        org.mockito.Mockito.verify(reportRepository)
                .findByRequestedByAndSourceOrderByRequestedAtDesc("supervisor1", "callcenter", org.springframework.data.domain.PageRequest.of(0, 20));
        verifyNoInteractions(snapshotRepository, aggregationService);
    }

    @Test
    @DisplayName("evolution de não-ADMIN só enxerga snapshots de relatórios próprios na origem pedida")
    void evolution_nonAdmin_emptyWhenNoOwnReportsInSource() {
        when(reportRepository.findIdsByAgentNameAndRequestedBy("Joao", "callcenter", "supervisor1"))
                .thenReturn(List.of());

        Optional<List<AgentEvolutionSnapshot>> result = service.evolution("Joao", "callcenter", "supervisor1", false);

        assertThat(result).isEmpty();
        verifyNoInteractions(snapshotRepository);
    }
}
