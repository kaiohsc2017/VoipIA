package com.asteriskia.domain.callcenter.quality;

import com.asteriskia.domain.BusinessDayCalculator;
import com.asteriskia.domain.insights.CallAudioFile;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallEvaluation;
import com.asteriskia.domain.insights.CallEvaluationItem;
import com.asteriskia.domain.insights.CallEvaluationItemRepository;
import com.asteriskia.domain.insights.CallEvaluationRepository;
import com.asteriskia.domain.insights.ScorecardItem;
import com.asteriskia.domain.insights.ScorecardItemRepository;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.asteriskia.domain.callcenter.recording.CcRecording;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CcQualityReportService — relatório de qualidade do Call Center (Fase 26 do plano omnicanal
 * Parte III): agrega {@code CallEvaluation}/{@code CallEvaluationItem} (Fase 8) já computados
 * pela IA quando a chamada foi avaliada contra uma ficha — não dispara nenhuma chamada de IA
 * nova, por isso não tem frente própria no Financeiro (§5.1 só se aplica a frentes de IA novas).
 * Distinto de {@code AgentReportService} (V39/Insights) — cooldown por escopo (agente/fila/geral),
 * não por par (supervisor, atendente).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcQualityReportService {

    private static final int COOLDOWN_BUSINESS_DAYS = 5;
    private static final String SOURCE = "callcenter";

    private final CcQualityReportRepository reportRepository;
    private final CcQualityReportSnapshotRepository snapshotRepository;
    private final CcHolidayRepository holidayRepository;
    private final CallAudioFileRepository audioFileRepository;
    private final CallEvaluationRepository evaluationRepository;
    private final CallEvaluationItemRepository evaluationItemRepository;
    private final ScorecardItemRepository scorecardItemRepository;
    private final CcRecordingRepository recordingRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public CcQualityReportDto requestReport(
            QualityReportScopeType scopeType, String scopeValue, LocalDate dateFrom, LocalDate dateTo,
            String requestedBy, boolean isAdmin) {
        String normalizedScopeValue = scopeType == QualityReportScopeType.GERAL ? null : scopeValue;
        if (scopeType != QualityReportScopeType.GERAL && (normalizedScopeValue == null || normalizedScopeValue.isBlank())) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "scopeValue é obrigatório para escopo " + scopeType);
        }

        if (!isAdmin) {
            enforceCooldown(scopeType, normalizedScopeValue);
        }

        CcQualityReport previous = reportRepository
                .findFirstByScopeTypeAndScopeValueAndSourceOrderByRequestedAtDesc(scopeType, normalizedScopeValue, SOURCE)
                .orElse(null);

        CcQualityReportContent content = buildContent(scopeType, normalizedScopeValue, dateFrom, dateTo);
        CcQualityReportEvolution evolution = buildEvolution(scopeType, normalizedScopeValue, content, previous);

        CcQualityReport report = reportRepository.save(CcQualityReport.builder()
                .source(SOURCE)
                .scopeType(scopeType)
                .scopeValue(normalizedScopeValue)
                .dateFrom(dateFrom)
                .dateTo(dateTo)
                .requestedBy(requestedBy)
                .notaMedia(content.notaMedia())
                .totalAvaliacoes(content.totalAvaliacoes())
                .totalReprovadas(content.totalReprovadas())
                .previousReportId(previous != null ? previous.getId() : null)
                .contentJson(writeJson(content))
                .scopedBuIds(currentScopedBuIdsForStorage())
                .build());

        saveSnapshots(report, content);

        log.info("Relatório de qualidade gerado: id={} scopeType={} scopeValue={} requestedBy={} período={}..{}",
                report.getId(), scopeType, normalizedScopeValue, requestedBy, dateFrom, dateTo);
        return toDto(report, content, evolution);
    }

    public Page<CcQualityReportDto> list(Pageable pageable) {
        // Baixa visibilidade real (poucas execuções na VPS de dev) — filtra em memória com
        // paginação manual, mesmo padrão já aceito em CallCenterDetailReportService.searchChats.
        List<CcQualityReport> visible = reportRepository.findBySourceOrderByRequestedAtDesc(SOURCE, Pageable.unpaged())
                .stream().filter(this::isVisibleToCurrentReader).toList();
        int from = Math.min((int) pageable.getOffset(), visible.size());
        int to = Math.min(from + pageable.getPageSize(), visible.size());
        List<CcQualityReportDto> pageContent = visible.subList(from, to).stream()
                .map(r -> toDto(r, readJson(r.getContentJson()), null)).toList();
        return new org.springframework.data.domain.PageImpl<>(pageContent, pageable, visible.size());
    }

    public Optional<CcQualityReportDto> getById(Long id) {
        return reportRepository.findById(id)
                .filter(r -> SOURCE.equals(r.getSource()))
                .filter(this::isVisibleToCurrentReader)
                .map(r -> {
                    CcQualityReportContent content = readJson(r.getContentJson());
                    CcQualityReport previous = r.getPreviousReportId() != null
                            ? reportRepository.findById(r.getPreviousReportId()).orElse(null)
                            : null;
                    CcQualityReportEvolution evolution = buildEvolution(r.getScopeType(), r.getScopeValue(), content, previous);
                    return toDto(r, content, evolution);
                });
    }

    /** ADMIN/leitor sem restrição de BU sempre vê tudo. Leitor restrito só vê relatórios cuja
     * geração também foi restrita (nunca um gerado por ADMIN, que pode ter agregado dado de
     * qualquer BU) e cujas BUs agregadas tenham interseção com as BUs do leitor atual — a
     * restrição de {@link #resolveAudioFileIds} protege a geração, esta protege a releitura. */
    private boolean isVisibleToCurrentReader(CcQualityReport report) {
        if (!BusinessUnitContext.isRestricted()) {
            return true;
        }
        if (report.getScopedBuIds() == null || report.getScopedBuIds().isBlank()) {
            return false;
        }
        Set<Integer> reportBuIds = parseScopedBuIds(report.getScopedBuIds());
        Set<Integer> readerBuIds = BusinessUnitContext.currentBusinessUnitIds();
        return reportBuIds.stream().anyMatch(readerBuIds::contains);
    }

    private String currentScopedBuIdsForStorage() {
        if (!BusinessUnitContext.isRestricted()) {
            return null;
        }
        Set<Integer> ids = BusinessUnitContext.currentBusinessUnitIds();
        return ids.isEmpty() ? null : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private Set<Integer> parseScopedBuIds(String raw) {
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Integer::parseInt).collect(Collectors.toSet());
    }

    public LocalDateTime nextAllowed(QualityReportScopeType scopeType, String scopeValue) {
        String normalizedScopeValue = scopeType == QualityReportScopeType.GERAL ? null : scopeValue;
        return reportRepository
                .findFirstByScopeTypeAndScopeValueAndSourceOrderByRequestedAtDesc(scopeType, normalizedScopeValue, SOURCE)
                .map(r -> BusinessDayCalculator.addBusinessDays(
                        r.getRequestedAt().toLocalDateTime(), COOLDOWN_BUSINESS_DAYS, holidayRepository.findAllDates()))
                .orElse(null);
    }

    public List<CcHoliday> listHolidays() {
        return holidayRepository.findAllByOrderByHolidayDateAsc();
    }

    @Transactional
    public CcHoliday createHoliday(LocalDate date, String description) {
        try {
            return holidayRepository.save(CcHoliday.builder().holidayDate(date).description(description).build());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Já existe um feriado cadastrado para " + date);
        }
    }

    @Transactional
    public void deleteHoliday(Long id) {
        if (!holidayRepository.existsById(id)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Feriado não encontrado: id=" + id);
        }
        holidayRepository.deleteById(id);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void enforceCooldown(QualityReportScopeType scopeType, String scopeValue) {
        LocalDateTime nextAllowedAt = nextAllowed(scopeType, scopeValue);
        if (nextAllowedAt != null && LocalDateTime.now().isBefore(nextAllowedAt)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Aguarde até " + nextAllowedAt + " para gerar outro relatório deste escopo.");
        }
    }

    /** Ids de call_audio_files elegíveis (source=callcenter, período, escopo) já restritos por
     * BU (join até cc_recordings.businessUnit, Fase 3/8 — não repete o gap aceito no Insights do
     * Call Center, Fase 8, que nunca aplicou esse filtro). */
    private List<Long> resolveAudioFileIds(QualityReportScopeType scopeType, String scopeValue, LocalDate from, LocalDate to) {
        Specification<CallAudioFile> spec = (root, query, cb) -> {
            var predicate = cb.equal(root.get("source"), SOURCE);
            predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("callStarttime"), LocalDateTime.of(from, LocalTime.MIN)));
            predicate = cb.and(predicate, cb.lessThanOrEqualTo(root.get("callStarttime"), LocalDateTime.of(to, LocalTime.MAX)));
            if (scopeType == QualityReportScopeType.AGENT) {
                predicate = cb.and(predicate, cb.equal(root.get("agentName"), scopeValue));
            } else if (scopeType == QualityReportScopeType.QUEUE) {
                predicate = cb.and(predicate, cb.equal(root.get("skill"), scopeValue));
            }
            return predicate;
        };
        List<CallAudioFile> candidates = audioFileRepository.findAll(spec);

        if (!BusinessUnitContext.isRestricted()) {
            return candidates.stream().map(CallAudioFile::getId).toList();
        }
        Set<Integer> allowedBuIds = BusinessUnitContext.currentBusinessUnitIds();
        List<Long> ccRecordingIds = candidates.stream()
                .map(CallAudioFile::getCcRecordingId).filter(Objects::nonNull).distinct().toList();
        Map<Long, CcRecording> recordingsById = recordingRepository.findAllById(ccRecordingIds).stream()
                .collect(Collectors.toMap(CcRecording::getId, r -> r));
        return candidates.stream()
                .filter(af -> {
                    if (af.getCcRecordingId() == null) {
                        // Nunca deveria acontecer para source=callcenter (CallCenterRecordingService
                        // sempre preenche ccRecordingId no ingest) — logado porque, diferente de
                        // businessUnit=null (decisão de design: BU opcional é visível a todos), aqui
                        // é "não sei a que BU pertence" liberado por padrão, e merece atenção se o
                        // invariante um dia for quebrado por outro caminho de ingestão.
                        log.warn("CallAudioFile id={} (source=callcenter) sem ccRecordingId — "
                                + "incluído no relatório de qualidade sem restrição de BU", af.getId());
                        return true;
                    }
                    CcRecording recording = recordingsById.get(af.getCcRecordingId());
                    return recording == null || recording.getBusinessUnit() == null
                            || allowedBuIds.contains(recording.getBusinessUnit().getId());
                })
                .map(CallAudioFile::getId)
                .toList();
    }

    private CcQualityReportContent buildContent(
            QualityReportScopeType scopeType, String scopeValue, LocalDate from, LocalDate to) {
        List<Long> audioFileIds = resolveAudioFileIds(scopeType, scopeValue, from, to);
        if (audioFileIds.isEmpty()) {
            return new CcQualityReportContent(null, 0, 0, List.of());
        }

        List<CallEvaluation> evaluations = evaluationRepository.findByAudioFileIdIn(audioFileIds);
        if (evaluations.isEmpty()) {
            return new CcQualityReportContent(null, 0, 0, List.of());
        }

        BigDecimal notaMedia = average(evaluations.stream().map(CallEvaluation::getNotaTotal).toList());
        int totalReprovadas = (int) evaluations.stream().filter(e -> Boolean.TRUE.equals(e.getIsFailed())).count();

        List<Long> evaluationIds = evaluations.stream().map(CallEvaluation::getId).toList();
        List<CallEvaluationItem> items = evaluationItemRepository.findByEvaluationIdIn(evaluationIds);
        Map<Long, List<BigDecimal>> notasPorItem = items.stream()
                .collect(Collectors.groupingBy(CallEvaluationItem::getItemId,
                        Collectors.mapping(CallEvaluationItem::getNota, Collectors.toList())));
        Map<Long, ScorecardItem> scorecardItemsById = scorecardItemRepository.findAllById(notasPorItem.keySet()).stream()
                .collect(Collectors.toMap(ScorecardItem::getId, si -> si));

        List<CcQualityReportContent.ItemAverage> notaPorItem = notasPorItem.entrySet().stream()
                .map(e -> new CcQualityReportContent.ItemAverage(
                        e.getKey(),
                        Optional.ofNullable(scorecardItemsById.get(e.getKey())).map(ScorecardItem::getPergunta).orElse(null),
                        average(e.getValue())))
                .sorted(Comparator.comparing(
                        i -> Optional.ofNullable(scorecardItemsById.get(i.itemId())).map(ScorecardItem::getOrdem).orElse(Integer.MAX_VALUE)))
                .toList();

        return new CcQualityReportContent(notaMedia, evaluations.size(), totalReprovadas, notaPorItem);
    }

    private CcQualityReportEvolution buildEvolution(
            QualityReportScopeType scopeType, String scopeValue, CcQualityReportContent current, CcQualityReport previous) {
        if (previous == null) {
            return null;
        }
        CcQualityReportContent previousContent = readJson(previous.getContentJson());
        BigDecimal notaMediaDelta = delta(previousContent.notaMedia(), current.notaMedia());

        Map<Long, BigDecimal> previousByItem = previousContent.notaPorItem().stream()
                .collect(Collectors.toMap(CcQualityReportContent.ItemAverage::itemId, CcQualityReportContent.ItemAverage::media));
        List<CcQualityReportEvolution.ItemDelta> itens = current.notaPorItem().stream()
                .map(item -> new CcQualityReportEvolution.ItemDelta(
                        item.itemId(), item.pergunta(), previousByItem.get(item.itemId()), item.media(),
                        delta(previousByItem.get(item.itemId()), item.media())))
                .toList();
        return new CcQualityReportEvolution(previousContent.notaMedia(), notaMediaDelta, itens);
    }

    private void saveSnapshots(CcQualityReport report, CcQualityReportContent content) {
        List<CcQualityReportSnapshot> snapshots = new ArrayList<>();
        if (content.notaMedia() != null) {
            snapshots.add(CcQualityReportSnapshot.builder()
                    .reportId(report.getId()).scopeType(report.getScopeType()).scopeValue(report.getScopeValue())
                    .itemId(null).metricKey("nota_total").valor(content.notaMedia()).build());
        }
        content.notaPorItem().forEach(item -> snapshots.add(CcQualityReportSnapshot.builder()
                .reportId(report.getId()).scopeType(report.getScopeType()).scopeValue(report.getScopeValue())
                .itemId(item.itemId()).metricKey("item_" + item.itemId()).valor(item.media()).build()));
        if (!snapshots.isEmpty()) {
            snapshotRepository.saveAll(snapshots);
        }
    }

    private BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> nonNull = values.stream().filter(Objects::nonNull).toList();
        if (nonNull.isEmpty()) return null;
        BigDecimal sum = nonNull.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(nonNull.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal delta(BigDecimal before, BigDecimal after) {
        if (before == null || after == null) return null;
        return after.subtract(before).setScale(2, RoundingMode.HALF_UP);
    }

    private CcQualityReportDto toDto(CcQualityReport r, CcQualityReportContent content, CcQualityReportEvolution evolution) {
        return new CcQualityReportDto(
                r.getId(), r.getScopeType(), r.getScopeValue(), r.getDateFrom(), r.getDateTo(),
                r.getRequestedBy(), r.getRequestedAt(), content, r.getPreviousReportId(), evolution);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar conteúdo do relatório de qualidade", e);
        }
    }

    private CcQualityReportContent readJson(String json) {
        try {
            return objectMapper.readValue(json, CcQualityReportContent.class);
        } catch (Exception e) {
            log.warn("Falha ao desserializar conteúdo do relatório de qualidade, retornando vazio: {}", e.getMessage());
            return new CcQualityReportContent(null, 0, 0, List.of());
        }
    }
}
