package com.asteriskia.domain.callcenter.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.insights.CallAudioFile;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallEvaluation;
import com.asteriskia.domain.insights.CallEvaluationItem;
import com.asteriskia.domain.insights.CallEvaluationItemRepository;
import com.asteriskia.domain.insights.CallEvaluationRepository;
import com.asteriskia.domain.insights.ScorecardItem;
import com.asteriskia.domain.insights.ScorecardItemRepository;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Cobre a regra de negócio do relatório de qualidade (Fase 26): agregação de notas já
 * existentes, cooldown por escopo, evolução contra a execução anterior — não testa o controller
 * (mesma convenção já estabelecida no domínio callcenter nesta sessão). */
@ExtendWith(MockitoExtension.class)
class CcQualityReportServiceTest {

    @Mock private CcQualityReportRepository reportRepository;
    @Mock private CcQualityReportSnapshotRepository snapshotRepository;
    @Mock private CcHolidayRepository holidayRepository;
    @Mock private CallAudioFileRepository audioFileRepository;
    @Mock private CallEvaluationRepository evaluationRepository;
    @Mock private CallEvaluationItemRepository evaluationItemRepository;
    @Mock private ScorecardItemRepository scorecardItemRepository;
    @Mock private CcRecordingRepository recordingRepository;

    private CcQualityReportService service;

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 14);

    @BeforeEach
    void setUp() {
        service = new CcQualityReportService(
                reportRepository, snapshotRepository, holidayRepository, audioFileRepository,
                evaluationRepository, evaluationItemRepository, scorecardItemRepository,
                recordingRepository, new ObjectMapper());
        lenient().when(holidayRepository.findAllDates()).thenReturn(java.util.Set.of());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void restrictToBusinessUnits(int... buIds) {
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        for (int id : buIds) {
            authorities.add(new SimpleGrantedAuthority("BU_" + id));
        }
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, authorities));
    }

    @Test
    @DisplayName("escopo AGENT/QUEUE sem scopeValue é rejeitado com 400")
    void requestReport_agentScopeWithoutValue_rejectsWith400() {
        assertThatThrownBy(() -> service.requestReport(
                QualityReportScopeType.AGENT, null, FROM, TO, "supervisor1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("scopeValue");
    }

    @Test
    @DisplayName("sem avaliação nenhuma no escopo/período, retorna conteúdo vazio sem erro")
    void requestReport_noEvaluations_returnsEmptyContent() {
        when(audioFileRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of());
        when(reportRepository.findFirstByScopeTypeAndScopeValueAndSourceOrderByRequestedAtDesc(
                QualityReportScopeType.GERAL, null, "callcenter")).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> {
            CcQualityReport r = inv.getArgument(0);
            r.setId(1L);
            r.setRequestedAt(OffsetDateTime.now());
            return r;
        });

        CcQualityReportDto dto = service.requestReport(QualityReportScopeType.GERAL, null, FROM, TO, "supervisor1", false);

        assertThat(dto.content().notaMedia()).isNull();
        assertThat(dto.content().totalAvaliacoes()).isZero();
        assertThat(dto.evolution()).isNull();
    }

    @Test
    @DisplayName("agrega nota média, reprovadas e nota por item corretamente")
    void requestReport_withEvaluations_aggregatesCorrectly() {
        CallAudioFile audioFile1 = CallAudioFile.builder().id(10L).source("callcenter").agentName("Kaio").build();
        CallAudioFile audioFile2 = CallAudioFile.builder().id(11L).source("callcenter").agentName("Kaio").build();
        when(audioFileRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(audioFile1, audioFile2));

        CallEvaluation eval1 = CallEvaluation.builder().id(100L).audioFileId(10L)
                .notaTotal(new BigDecimal("80.00")).isFailed(false).build();
        CallEvaluation eval2 = CallEvaluation.builder().id(101L).audioFileId(11L)
                .notaTotal(new BigDecimal("60.00")).isFailed(true).build();
        when(evaluationRepository.findByAudioFileIdIn(List.of(10L, 11L))).thenReturn(List.of(eval1, eval2));

        CallEvaluationItem item1a = CallEvaluationItem.builder().id(1L).evaluationId(100L).itemId(500L)
                .nota(new BigDecimal("8")).build();
        CallEvaluationItem item1b = CallEvaluationItem.builder().id(2L).evaluationId(101L).itemId(500L)
                .nota(new BigDecimal("6")).build();
        when(evaluationItemRepository.findByEvaluationIdIn(List.of(100L, 101L))).thenReturn(List.of(item1a, item1b));
        when(scorecardItemRepository.findAllById(java.util.Set.of(500L))).thenReturn(
                List.of(ScorecardItem.builder().id(500L).pergunta("Saudação adequada?").ordem(1).build()));

        when(reportRepository.findFirstByScopeTypeAndScopeValueAndSourceOrderByRequestedAtDesc(
                QualityReportScopeType.AGENT, "Kaio", "callcenter")).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> {
            CcQualityReport r = inv.getArgument(0);
            r.setId(2L);
            r.setRequestedAt(OffsetDateTime.now());
            return r;
        });

        CcQualityReportDto dto = service.requestReport(QualityReportScopeType.AGENT, "Kaio", FROM, TO, "supervisor1", false);

        assertThat(dto.content().notaMedia()).isEqualByComparingTo("70.00");
        assertThat(dto.content().totalAvaliacoes()).isEqualTo(2);
        assertThat(dto.content().totalReprovadas()).isEqualTo(1);
        assertThat(dto.content().notaPorItem()).hasSize(1);
        assertThat(dto.content().notaPorItem().get(0).media()).isEqualByComparingTo("7.00");
        assertThat(dto.content().notaPorItem().get(0).pergunta()).isEqualTo("Saudação adequada?");
    }

    @Test
    @DisplayName("segunda execução no mesmo escopo calcula evolução (delta) contra a anterior")
    void requestReport_secondExecution_calculatesEvolutionDelta() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CcQualityReportContent previousContent = new CcQualityReportContent(
                new BigDecimal("70.00"), 2, 1,
                List.of(new CcQualityReportContent.ItemAverage(500L, "Saudação adequada?", new BigDecimal("7.00"))));
        CcQualityReport previous = CcQualityReport.builder().id(1L).scopeType(QualityReportScopeType.AGENT)
                .scopeValue("Kaio").source("callcenter").notaMedia(new BigDecimal("70.00"))
                .requestedAt(OffsetDateTime.now().minusDays(10))
                .contentJson(mapper.writeValueAsString(previousContent)).build();

        CallAudioFile audioFile = CallAudioFile.builder().id(20L).source("callcenter").agentName("Kaio").build();
        when(audioFileRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(audioFile));
        CallEvaluation eval = CallEvaluation.builder().id(200L).audioFileId(20L)
                .notaTotal(new BigDecimal("90.00")).isFailed(false).build();
        when(evaluationRepository.findByAudioFileIdIn(List.of(20L))).thenReturn(List.of(eval));
        CallEvaluationItem item = CallEvaluationItem.builder().id(3L).evaluationId(200L).itemId(500L)
                .nota(new BigDecimal("9")).build();
        when(evaluationItemRepository.findByEvaluationIdIn(List.of(200L))).thenReturn(List.of(item));
        when(scorecardItemRepository.findAllById(java.util.Set.of(500L))).thenReturn(
                List.of(ScorecardItem.builder().id(500L).pergunta("Saudação adequada?").ordem(1).build()));

        when(reportRepository.findFirstByScopeTypeAndScopeValueAndSourceOrderByRequestedAtDesc(
                QualityReportScopeType.AGENT, "Kaio", "callcenter")).thenReturn(Optional.of(previous));
        when(reportRepository.save(any())).thenAnswer(inv -> {
            CcQualityReport r = inv.getArgument(0);
            r.setId(2L);
            r.setRequestedAt(OffsetDateTime.now());
            return r;
        });

        CcQualityReportDto dto = service.requestReport(QualityReportScopeType.AGENT, "Kaio", FROM, TO, "supervisor1", false);

        assertThat(dto.evolution()).isNotNull();
        assertThat(dto.evolution().notaMediaAnterior()).isEqualByComparingTo("70.00");
        assertThat(dto.evolution().notaMediaDelta()).isEqualByComparingTo("20.00");
        assertThat(dto.evolution().itens()).hasSize(1);
        assertThat(dto.evolution().itens().get(0).delta()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("cooldown de 5 dias úteis bloqueia não-admin dentro da janela, considerando feriados")
    void requestReport_withinCooldown_blocksNonAdmin() {
        OffsetDateTime requestedAt = OffsetDateTime.now().minusHours(1);
        CcQualityReport recent = CcQualityReport.builder().id(1L).scopeType(QualityReportScopeType.GERAL)
                .scopeValue(null).source("callcenter").requestedAt(requestedAt).build();
        when(reportRepository.findFirstByScopeTypeAndScopeValueAndSourceOrderByRequestedAtDesc(
                QualityReportScopeType.GERAL, null, "callcenter")).thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> service.requestReport(QualityReportScopeType.GERAL, null, FROM, TO, "supervisor1", false))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("ADMIN é isento do cooldown, mesmo dentro da janela de 5 dias úteis")
    void requestReport_adminExemptFromCooldown() {
        OffsetDateTime requestedAt = OffsetDateTime.now().minusHours(1);
        CcQualityReport recent = CcQualityReport.builder().id(1L).scopeType(QualityReportScopeType.GERAL)
                .scopeValue(null).source("callcenter").requestedAt(requestedAt).build();
        when(reportRepository.findFirstByScopeTypeAndScopeValueAndSourceOrderByRequestedAtDesc(
                QualityReportScopeType.GERAL, null, "callcenter")).thenReturn(Optional.of(recent));
        when(audioFileRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(List.of());
        when(reportRepository.save(any())).thenAnswer(inv -> {
            CcQualityReport r = inv.getArgument(0);
            r.setId(2L);
            r.setRequestedAt(OffsetDateTime.now());
            return r;
        });

        CcQualityReportDto dto = service.requestReport(QualityReportScopeType.GERAL, null, FROM, TO, "admin", true);

        assertThat(dto.id()).isEqualTo(2L);
    }

    @Test
    @DisplayName("leitor restrito por BU não enxerga relatório gerado sem interseção de BU (getById)")
    void getById_restrictedReaderWithoutOverlap_returnsEmpty() {
        restrictToBusinessUnits(2);
        CcQualityReport report = CcQualityReport.builder().id(5L).source("callcenter")
                .scopeType(QualityReportScopeType.GERAL).scopedBuIds("1")
                .contentJson("{\"notaMedia\":null,\"totalAvaliacoes\":0,\"totalReprovadas\":0,\"notaPorItem\":[]}").build();
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));

        assertThat(service.getById(5L)).isEmpty();
    }

    @Test
    @DisplayName("leitor restrito por BU enxerga relatório com interseção de BU (getById)")
    void getById_restrictedReaderWithOverlap_returnsReport() {
        restrictToBusinessUnits(1, 3);
        CcQualityReport report = CcQualityReport.builder().id(6L).source("callcenter")
                .scopeType(QualityReportScopeType.GERAL).scopedBuIds("1,2")
                .contentJson("{\"notaMedia\":null,\"totalAvaliacoes\":0,\"totalReprovadas\":0,\"notaPorItem\":[]}").build();
        when(reportRepository.findById(6L)).thenReturn(Optional.of(report));

        assertThat(service.getById(6L)).isPresent();
    }

    @Test
    @DisplayName("leitor restrito por BU não enxerga relatório gerado sem nenhuma restrição (ADMIN)")
    void getById_restrictedReader_cannotSeeUnrestrictedGeneratedReport() {
        restrictToBusinessUnits(1);
        CcQualityReport report = CcQualityReport.builder().id(7L).source("callcenter")
                .scopeType(QualityReportScopeType.GERAL).scopedBuIds(null)
                .contentJson("{\"notaMedia\":null,\"totalAvaliacoes\":0,\"totalReprovadas\":0,\"notaPorItem\":[]}").build();
        when(reportRepository.findById(7L)).thenReturn(Optional.of(report));

        assertThat(service.getById(7L)).isEmpty();
    }

    @Test
    @DisplayName("ADMIN sempre enxerga qualquer relatório, restrito ou não")
    void getById_admin_alwaysSeesAnyReport() {
        restrictToBusinessUnits();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        CcQualityReport report = CcQualityReport.builder().id(8L).source("callcenter")
                .scopeType(QualityReportScopeType.GERAL).scopedBuIds("1")
                .contentJson("{\"notaMedia\":null,\"totalAvaliacoes\":0,\"totalReprovadas\":0,\"notaPorItem\":[]}").build();
        when(reportRepository.findById(8L)).thenReturn(Optional.of(report));

        assertThat(service.getById(8L)).isPresent();
    }
}
