package com.asteriskia.domain.call;

import com.asteriskia.domain.ai.AiModelPricing;
import com.asteriskia.domain.ai.AiModelPricingRepository;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
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
 * CallCostService — custo estimado de IA por chamada e agregação mensal (Módulo 1 → aba Custos
 * IA / Dashboard de Custos). Reusa CallRecordSpecifications/CallRecordFilter da aba Chamadas, mas
 * fica em service próprio (independente de CallRecordService) por responsabilidade distinta:
 * cálculo de custo, não gestão do ciclo de vida da chamada/Jira.
 */
@Service
@RequiredArgsConstructor
public class CallCostService {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final CallRecordRepository callRecordRepository;
    private final AiModelPricingRepository pricingRepository;

    @Transactional(readOnly = true)
    public Page<CallCostView> findCosts(CallRecordFilter filter, Pageable pageable) {
        Page<CallRecord> page =
                callRecordRepository.findAll(
                        CallRecordSpecifications.withFilters(filter).and(businessUnitScope()),
                        pageable);
        Map<String, AiModelPricing> pricing = loadPricingMap();
        return page.map(r -> CallCostView.from(r, computeCallCost(r, pricing)));
    }

    @Transactional(readOnly = true)
    public List<MonthlyCostSummary> summarizeByMonth(CallRecordFilter filter) {
        List<CallRecord> records =
                callRecordRepository.findAll(
                        CallRecordSpecifications.withFilters(filter).and(businessUnitScope()));
        Map<String, AiModelPricing> pricing = loadPricingMap();

        Map<String, List<CallRecord>> byMonth =
                records.stream()
                        .collect(
                                Collectors.groupingBy(
                                        r -> YearMonth.from(r.getCallDate()).toString()));

        return byMonth.entrySet().stream()
                .map(e -> summarizeMonth(e.getKey(), e.getValue(), pricing))
                .sorted(Comparator.comparing(MonthlyCostSummary::month))
                .toList();
    }

    private MonthlyCostSummary summarizeMonth(
            String month, List<CallRecord> records, Map<String, AiModelPricing> pricing) {
        BigDecimal stt = BigDecimal.ZERO, llm = BigDecimal.ZERO, tts = BigDecimal.ZERO;
        for (CallRecord r : records) {
            stt = stt.add(costFor(pricing, r.getSttModel(), r.getSttTokensIn(), r.getSttTokensOut()));
            llm = llm.add(costFor(pricing, r.getLlmModel(), r.getLlmTokensIn(), r.getLlmTokensOut()));
            tts = tts.add(costFor(pricing, r.getTtsModel(), r.getTtsTokensIn(), r.getTtsTokensOut()));
        }
        return new MonthlyCostSummary(month, stt, llm, tts, stt.add(llm).add(tts), records.size());
    }

    private BigDecimal computeCallCost(CallRecord r, Map<String, AiModelPricing> pricing) {
        return costFor(pricing, r.getSttModel(), r.getSttTokensIn(), r.getSttTokensOut())
                .add(costFor(pricing, r.getLlmModel(), r.getLlmTokensIn(), r.getLlmTokensOut()))
                .add(costFor(pricing, r.getTtsModel(), r.getTtsTokensIn(), r.getTtsTokensOut()));
    }

    /** Custo de uma capability (STT/LLM/TTS) de uma chamada — 0 se o modelo não tem preço cadastrado. */
    private BigDecimal costFor(
            Map<String, AiModelPricing> pricing, String modelId, Integer tokensIn, Integer tokensOut) {
        if (modelId == null) return BigDecimal.ZERO;
        AiModelPricing price = pricing.get(modelId);
        if (price == null) return BigDecimal.ZERO;

        BigDecimal inCost =
                price.getPricePerMillionInputUsd()
                        .multiply(BigDecimal.valueOf(tokensIn != null ? tokensIn : 0))
                        .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal outCost =
                price.getPricePerMillionOutputUsd()
                        .multiply(BigDecimal.valueOf(tokensOut != null ? tokensOut : 0))
                        .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        return inCost.add(outCost);
    }

    private Map<String, AiModelPricing> loadPricingMap() {
        return pricingRepository.findAll().stream()
                .collect(Collectors.toMap(AiModelPricing::getModelId, p -> p));
    }

    /** Mesma regra de escopo por BU de CallRecordService — duplicada aqui para manter os dois
     * services independentes (CallCostService não depende de CallRecordService). */
    private Specification<CallRecord> businessUnitScope() {
        if (!BusinessUnitContext.isRestricted()) {
            return Specification.where(null);
        }
        return CallRecordSpecifications.restrictedToBusinessUnits(
                BusinessUnitContext.currentBusinessUnitIds());
    }
}
