package com.asteriskia.domain.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentReportAggregationService — monta o agregado numérico (médias, achados, itens
 * de ficha) e a evolução em relação ao relatório anterior do mesmo agente, sempre em
 * SQL/Java (Fase 2 do Quality Management, V39). O serviço Python só recebe este
 * agregado já pronto para narrar — nunca recalcula nem inventa números.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentReportAggregationService {

    private static final int TOP_FINDINGS_LIMIT = 15;

    private final CallAudioFileRepository audioFileRepository;
    private final CallEvaluationRepository evaluationRepository;
    private final CallEvaluationItemRepository evaluationItemRepository;
    private final CallInsightFindingRepository findingRepository;
    private final ObjectMapper objectMapper;

    public AgentReportContent buildAggregate(String agentName, LocalDate dateFrom, LocalDate dateTo) {
        LocalDateTime from = dateFrom.atStartOfDay();
        LocalDateTime to = dateTo.atTime(LocalTime.MAX);

        long totalChamadas = audioFileRepository.countByAgentNameAndCallStarttimeBetween(agentName, from, to);
        BigDecimal notaMedia = evaluationRepository.averageNotaForAgentPeriod(agentName, from, to);
        long autoFails = evaluationRepository.countFailedForAgentPeriod(agentName, from, to);

        List<AgentReportContent.ItemAverage> notaPorItem = evaluationItemRepository
                .averageNotaByItemForAgentPeriod(agentName, from, to).stream()
                .map(row -> new AgentReportContent.ItemAverage(
                        (Long) row[0], (String) row[1], round((BigDecimal) row[2])))
                .toList();

        Map<String, Long> achadosPorTipo = new HashMap<>();
        for (Object[] row : findingRepository.countByTipoForAgentPeriod(agentName, from, to)) {
            achadosPorTipo.put((String) row[0], (Long) row[1]);
        }

        List<AgentReportContent.Finding> achadosGraves = findingRepository
                .findTopForAgentPeriod(agentName, from, to, PageRequest.of(0, TOP_FINDINGS_LIMIT)).stream()
                .map(f -> new AgentReportContent.Finding(f.getTipo(), f.getDescricao(), f.getTrechoReferencia(), f.getPrioridade()))
                .toList();

        return new AgentReportContent(
                new AgentReportContent.Aggregate(totalChamadas, round(notaMedia), autoFails, notaPorItem, achadosPorTipo),
                achadosGraves,
                null);
    }

    /** Delta entre o agregado atual e o do relatório anterior (se houver) — sempre calculado
     * aqui, nunca aceito pronto do LLM. Sinaliza comparação parcial se os itens de ficha
     * usados nos dois relatórios não baterem (ficha foi trocada entre os dois períodos). */
    public AgentReportEvolution buildEvolution(AgentReportContent current, AgentPerformanceReport previous) {
        if (previous == null) {
            return null;
        }
        AgentReportContent previousContent = parseContent(previous.getContentJson());
        if (previousContent == null || previousContent.aggregate() == null) {
            return null;
        }

        Map<Long, AgentReportContent.ItemAverage> previousByItem = previousContent.aggregate().notaPorItem().stream()
                .collect(java.util.stream.Collectors.toMap(AgentReportContent.ItemAverage::itemId, i -> i));
        Map<Long, AgentReportContent.ItemAverage> currentByItem = current.aggregate().notaPorItem().stream()
                .collect(java.util.stream.Collectors.toMap(AgentReportContent.ItemAverage::itemId, i -> i));

        boolean partial = !previousByItem.keySet().equals(currentByItem.keySet());

        List<AgentReportEvolution.ItemDelta> deltas = currentByItem.values().stream()
                .map(item -> {
                    AgentReportContent.ItemAverage prev = previousByItem.get(item.itemId());
                    BigDecimal anterior = prev != null ? prev.media() : null;
                    BigDecimal delta = anterior != null ? item.media().subtract(anterior) : null;
                    return new AgentReportEvolution.ItemDelta(item.itemId(), item.pergunta(), anterior, item.media(), delta);
                })
                .toList();

        BigDecimal notaAnterior = previousContent.aggregate().notaMedia();
        BigDecimal notaAtual = current.aggregate().notaMedia();
        BigDecimal deltaNotaMedia = (notaAnterior != null && notaAtual != null) ? notaAtual.subtract(notaAnterior) : null;

        return new AgentReportEvolution(previous.getId(), partial, deltaNotaMedia, deltas);
    }

    private AgentReportContent parseContent(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AgentReportContent.class);
        } catch (Exception e) {
            log.warn("Falha ao desserializar content_json de relatório anterior — evolução ignorada", e);
            return null;
        }
    }

    private BigDecimal round(BigDecimal value) {
        return value != null ? value.setScale(2, RoundingMode.HALF_UP) : null;
    }
}
