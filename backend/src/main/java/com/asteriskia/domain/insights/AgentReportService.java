package com.asteriskia.domain.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.asteriskia.domain.BusinessDayCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * AgentReportService — regra de negócio dos relatórios de performance por atendente
 * (Fase 2 do Quality Management, V39): cooldown de 5 dias úteis por par
 * (supervisor, atendente) com ADMIN isento, resolução do previous_report_id antes de
 * enfileirar, posse sempre por requestedBy (username — JWT não tem user-id).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentReportService {

    private static final int COOLDOWN_BUSINESS_DAYS = 5;

    private final AgentPerformanceReportRepository reportRepository;
    private final AgentEvolutionSnapshotRepository snapshotRepository;
    private final AgentReportAggregationService aggregationService;
    private final ObjectMapper objectMapper;

    @Transactional
    public AgentReportDto requestReport(String agentName, String source, LocalDate dateFrom, LocalDate dateTo,
                                         String requestedBy, boolean isAdmin) {
        if (!isAdmin) {
            enforceCooldown(agentName, source, requestedBy);
        }

        AgentPerformanceReport previous = reportRepository
                .findFirstByAgentNameAndSourceAndStatusOrderByCompletedAtDesc(agentName, source, "done")
                .orElse(null);

        AgentReportContent currentContent = aggregationService.buildAggregate(agentName, source, dateFrom, dateTo);
        AgentReportEvolution evolution = aggregationService.buildEvolution(currentContent, previous);

        AgentPerformanceReport report;
        try {
            report = reportRepository.save(AgentPerformanceReport.builder()
                    .agentName(agentName)
                    .source(source)
                    .dateFrom(dateFrom)
                    .dateTo(dateTo)
                    .requestedBy(requestedBy)
                    .status("pending")
                    .contentJson(writeJson(currentContent))
                    .evolutionJson(writeJson(evolution))
                    .previousReportId(previous != null ? previous.getId() : null)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Cinturão de segurança do índice único parcial (requested_by, agent_name, source) em voo —
            // corrida de dois pedidos simultâneos do mesmo par (checagem de aplicação já passou).
            throw new IllegalStateException("Já existe um relatório em processamento para este atendente.", e);
        }

        saveEvolutionSnapshots(report, currentContent);

        log.info("Relatório de performance solicitado: id={} agentName={} source={} requestedBy={} período={}..{}",
                report.getId(), agentName, source, requestedBy, dateFrom, dateTo);
        return toDto(report);
    }

    public Page<AgentReportDto> list(String requestedBy, String source, boolean isAdmin, Pageable pageable) {
        Page<AgentPerformanceReport> page = isAdmin
                ? reportRepository.findBySourceOrderByRequestedAtDesc(source, pageable)
                : reportRepository.findByRequestedByAndSourceOrderByRequestedAtDesc(requestedBy, source, pageable);
        return page.map(this::toDto);
    }

    /** 404 (não 403) para relatório alheio — não vaza existência a quem não é dono nem ADMIN.
     * Filtra por source para um relatório de Verint nunca aparecer pela API do Call Center
     * (ou vice-versa), mesmo que o id exista. */
    public Optional<AgentReportDto> getById(Long id, String source, String requestedBy, boolean isAdmin) {
        return reportRepository.findById(id)
                .filter(r -> source.equals(r.getSource()))
                .filter(r -> isAdmin || r.getRequestedBy().equals(requestedBy))
                .map(this::toDto);
    }

    Optional<AgentPerformanceReport> findOwnedEntity(Long id, String source, String requestedBy, boolean isAdmin) {
        return reportRepository.findById(id)
                .filter(r -> source.equals(r.getSource()))
                .filter(r -> isAdmin || r.getRequestedBy().equals(requestedBy));
    }

    public LocalDateTime nextAllowed(String agentName, String source, String requestedBy) {
        return reportRepository.findFirstByRequestedByAndAgentNameAndSourceOrderByRequestedAtDesc(requestedBy, agentName, source)
                .map(r -> BusinessDayCalculator.addBusinessDays(r.getRequestedAt().toLocalDateTime(), COOLDOWN_BUSINESS_DAYS))
                .orElse(null);
    }

    /** Não-ADMIN só vê os pontos de evolução dos relatórios que ele mesmo pediu para este
     * agente — mesma regra de posse aplicada em getById()/list(), mesmo que o agente já
     * tenha sido reportado por outro supervisor também. */
    public Optional<List<AgentEvolutionSnapshot>> evolution(String agentName, String source, String requestedBy, boolean isAdmin) {
        if (isAdmin) {
            // GAP CONHECIDO: agent_evolution_snapshots não tem coluna source (V39) — um ADMIN
            // pode ver pontos de evolução de verint e callcenter juntos se o agentName coincidir
            // entre os dois universos. Mesmo padrão de gap já aceito em outras telas cross-source.
            return Optional.of(snapshotRepository.findByAgentNameOrderByCreatedAtAsc(agentName));
        }
        List<Long> ownReportIds = reportRepository.findIdsByAgentNameAndRequestedBy(agentName, source, requestedBy);
        if (ownReportIds.isEmpty()) {
            return Optional.empty();
        }
        List<AgentEvolutionSnapshot> ownSnapshots = snapshotRepository.findByAgentNameOrderByCreatedAtAsc(agentName).stream()
                .filter(s -> ownReportIds.contains(s.getReportId()))
                .toList();
        return Optional.of(ownSnapshots);
    }

    // ─── Consumido pelo serviço asteriskia-insights (endpoints internos) ─────────

    public List<AgentPerformanceReport> findPending() {
        return reportRepository.findByStatus("pending");
    }

    @Transactional
    public void markProcessing(Long id) {
        reportRepository.findById(id).ifPresent(r -> {
            r.setStatus("processing");
            reportRepository.save(r);
        });
    }

    @Transactional
    public void markError(Long id, String errorMsg) {
        reportRepository.findById(id).ifPresent(r -> {
            r.setStatus("error");
            r.setErrorMsg(errorMsg);
            reportRepository.save(r);
        });
    }

    @Transactional
    public void submitNarrative(Long id, AgentReportContent.Narrative narrative,
                                 Integer llmTokensIn, Integer llmTokensOut, String llmModel) {
        AgentPerformanceReport report = reportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Relatório não encontrado: id=" + id));
        AgentReportContent existing = readJson(report.getContentJson(), AgentReportContent.class);
        AgentReportContent updated = new AgentReportContent(existing.aggregate(), existing.achadosGraves(), narrative);
        report.setContentJson(writeJson(updated));
        report.setLlmTokensIn(llmTokensIn != null ? llmTokensIn : 0);
        report.setLlmTokensOut(llmTokensOut != null ? llmTokensOut : 0);
        report.setLlmModel(llmModel);
        report.setStatus("done");
        report.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        reportRepository.save(report);
        log.info("Narrativa de relatório persistida: id={}", id);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void enforceCooldown(String agentName, String source, String requestedBy) {
        LocalDateTime nextAllowedAt = nextAllowed(agentName, source, requestedBy);
        if (nextAllowedAt != null && LocalDateTime.now().isBefore(nextAllowedAt)) {
            throw new ReportCooldownException(nextAllowedAt);
        }
    }

    private void saveEvolutionSnapshots(AgentPerformanceReport report, AgentReportContent content) {
        List<AgentEvolutionSnapshot> snapshots = new java.util.ArrayList<>();
        if (content.aggregate().notaMedia() != null) {
            snapshots.add(AgentEvolutionSnapshot.builder()
                    .agentName(report.getAgentName())
                    .reportId(report.getId())
                    .itemId(null)
                    .metricKey("nota_total")
                    .valor(content.aggregate().notaMedia())
                    .build());
        }
        content.aggregate().notaPorItem().forEach(item -> snapshots.add(AgentEvolutionSnapshot.builder()
                .agentName(report.getAgentName())
                .reportId(report.getId())
                .itemId(item.itemId())
                .metricKey("item_" + item.itemId())
                .valor(item.media())
                .build()));
        if (!snapshots.isEmpty()) {
            snapshotRepository.saveAll(snapshots);
        }
    }

    private AgentReportDto toDto(AgentPerformanceReport r) {
        return new AgentReportDto(
                r.getId(), r.getAgentName(), r.getDateFrom(), r.getDateTo(),
                r.getRequestedBy(), r.getRequestedAt(), r.getStatus(), r.getErrorMsg(),
                readJson(r.getContentJson(), AgentReportContent.class),
                r.getPreviousReportId(),
                readJson(r.getEvolutionJson(), AgentReportEvolution.class),
                r.getCompletedAt());
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar conteúdo do relatório", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Falha ao desserializar JSON de relatório ({}), retornando null", type.getSimpleName(), e);
            return null;
        }
    }
}
