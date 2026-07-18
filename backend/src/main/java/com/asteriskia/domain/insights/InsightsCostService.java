package com.asteriskia.domain.insights;

import com.asteriskia.domain.ai.AiModelPricing;
import com.asteriskia.domain.ai.AiModelPricingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * InsightsCostService — custo estimado de IA por chamada e agregação mensal do módulo
 * Insights (aba "Custos IA" / "Dashboard de Custos"). Mirror exato de CallCostService
 * (domain/call/), reusando a mesma AiModelPricingRepository (nenhuma tabela/lógica de
 * preço duplicada) — só STT+LLM, sem TTS.
 */
@Service
@RequiredArgsConstructor
public class InsightsCostService {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final CallAudioFileRepository audioFileRepository;
    private final AiModelPricingRepository pricingRepository;

    @Transactional(readOnly = true)
    public Page<InsightCostView> findCosts(InsightsCostFilter filter, Pageable pageable) {
        Page<CallAudioFile> page = audioFileRepository.findAll(withFilters(filter), pageable);
        Map<String, AiModelPricing> pricing = loadPricingMap();
        return page.map(a -> InsightCostView.from(a, computeCost(a, pricing)));
    }

    @Transactional(readOnly = true)
    public List<InsightMonthlyCostSummary> summarizeByMonth(InsightsCostFilter filter) {
        List<CallAudioFile> records = audioFileRepository.findAll(withFilters(filter));
        Map<String, AiModelPricing> pricing = loadPricingMap();

        Map<String, List<CallAudioFile>> byMonth = records.stream()
                .filter(a -> a.getCallStarttime() != null)
                .collect(Collectors.groupingBy(a -> YearMonth.from(a.getCallStarttime()).toString()));

        return byMonth.entrySet().stream()
                .map(e -> summarizeMonth(e.getKey(), e.getValue(), pricing))
                .sorted(Comparator.comparing(InsightMonthlyCostSummary::month))
                .toList();
    }

    private InsightMonthlyCostSummary summarizeMonth(
            String month, List<CallAudioFile> records, Map<String, AiModelPricing> pricing) {
        BigDecimal stt = BigDecimal.ZERO, llm = BigDecimal.ZERO;
        for (CallAudioFile a : records) {
            stt = stt.add(costFor(pricing, a.getSttModel(), a.getSttTokensIn(), a.getSttTokensOut()));
            llm = llm.add(costFor(pricing, a.getLlmModel(), a.getLlmTokensIn(), a.getLlmTokensOut()));
        }
        return new InsightMonthlyCostSummary(month, stt, llm, stt.add(llm), records.size());
    }

    private BigDecimal computeCost(CallAudioFile a, Map<String, AiModelPricing> pricing) {
        return costFor(pricing, a.getSttModel(), a.getSttTokensIn(), a.getSttTokensOut())
                .add(costFor(pricing, a.getLlmModel(), a.getLlmTokensIn(), a.getLlmTokensOut()));
    }

    private BigDecimal costFor(
            Map<String, AiModelPricing> pricing, String modelId, Integer tokensIn, Integer tokensOut) {
        if (modelId == null) return BigDecimal.ZERO;
        AiModelPricing price = pricing.get(modelId);
        if (price == null) return BigDecimal.ZERO;

        BigDecimal inCost = price.getPricePerMillionInputUsd()
                .multiply(BigDecimal.valueOf(tokensIn != null ? tokensIn : 0))
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal outCost = price.getPricePerMillionOutputUsd()
                .multiply(BigDecimal.valueOf(tokensOut != null ? tokensOut : 0))
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        return inCost.add(outCost);
    }

    private Map<String, AiModelPricing> loadPricingMap() {
        return pricingRepository.findAll().stream()
                .collect(Collectors.toMap(AiModelPricing::getModelId, p -> p));
    }

    private Specification<CallAudioFile> withFilters(InsightsCostFilter filter) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (filter.dateFrom() != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("callStarttime"), filter.dateFrom()));
            }
            if (filter.dateTo() != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("callStarttime"), filter.dateTo()));
            }
            if (filter.agentName() != null && !filter.agentName().isBlank()) {
                predicates = cb.and(predicates,
                        cb.like(cb.lower(root.get("agentName")), "%" + filter.agentName().toLowerCase() + "%"));
            }
            return predicates;
        };
    }
}
