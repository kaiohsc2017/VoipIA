package com.asteriskia.domain.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.ai.AiModelPricing;
import com.asteriskia.domain.ai.AiModelPricingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * CallCostServiceTest — cobre o cálculo de custo por chamada (multiplicação/divisão de tokens
 * pelo preço por milhão) e a agregação mensal, incluindo os casos de borda que mais quebram esse
 * tipo de conta: modelo sem preço cadastrado, tokens nulos, e chamadas em meses diferentes.
 */
@ExtendWith(MockitoExtension.class)
class CallCostServiceTest {

    @Mock private CallRecordRepository callRecordRepository;
    @Mock private AiModelPricingRepository pricingRepository;

    private CallCostService service;

    @BeforeEach
    void setUp() {
        service = new CallCostService(callRecordRepository, pricingRepository);
    }

    private static AiModelPricing pricing(String modelId, double in, double out) {
        return AiModelPricing.builder()
                .modelId(modelId)
                .provider("gemini")
                .pricePerMillionInputUsd(BigDecimal.valueOf(in))
                .pricePerMillionOutputUsd(BigDecimal.valueOf(out))
                .build();
    }

    private static CallRecord call(
            LocalDateTime date, int llmIn, int llmOut, String llmModel) {
        return CallRecord.builder()
                .id(1L)
                .uraId(1)
                .callUuid(UUID.randomUUID())
                .callDate(date)
                .callerNumber("11999999999")
                .llmTokensIn(llmIn)
                .llmTokensOut(llmOut)
                .llmModel(llmModel)
                .build();
    }

    @Test
    void findCosts_calculaCustoAPartirDoPrecoPorMilhaoDeTokens() {
        // 1_000_000 tokens de entrada a US$1/milhão = US$1,00; 500_000 de saída a US$2/milhão = US$1,00
        CallRecord record = call(LocalDateTime.now(), 1_000_000, 500_000, "gemini-2.5-flash");
        when(callRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(pricingRepository.findAll()).thenReturn(List.of(pricing("gemini-2.5-flash", 1.0, 2.0)));

        Page<CallCostView> result = service.findCosts(emptyFilter(), Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).estimatedCostUsd())
                .isEqualByComparingTo(BigDecimal.valueOf(2.0).setScale(6));
    }

    @Test
    void findCosts_modeloSemPrecoCadastrado_custoZeroSemQuebrar() {
        CallRecord record = call(LocalDateTime.now(), 1_000_000, 1_000_000, "modelo-desconhecido");
        when(callRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(pricingRepository.findAll()).thenReturn(List.of());

        Page<CallCostView> result = service.findCosts(emptyFilter(), Pageable.unpaged());

        assertThat(result.getContent().get(0).estimatedCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void summarizeByMonth_agrupaChamadasDeMesesDiferentes() {
        CallRecord jan = call(LocalDateTime.of(2026, 1, 15, 10, 0), 1_000_000, 0, "gemini-2.5-flash");
        CallRecord fev1 = call(LocalDateTime.of(2026, 2, 1, 9, 0), 1_000_000, 0, "gemini-2.5-flash");
        CallRecord fev2 = call(LocalDateTime.of(2026, 2, 20, 18, 0), 1_000_000, 0, "gemini-2.5-flash");
        when(callRecordRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(jan, fev1, fev2));
        when(pricingRepository.findAll()).thenReturn(List.of(pricing("gemini-2.5-flash", 1.0, 0.0)));

        List<MonthlyCostSummary> summary = service.summarizeByMonth(emptyFilter());

        assertThat(summary).hasSize(2);
        assertThat(summary.get(0).month()).isEqualTo("2026-01");
        assertThat(summary.get(0).callCount()).isEqualTo(1);
        assertThat(summary.get(1).month()).isEqualTo("2026-02");
        assertThat(summary.get(1).callCount()).isEqualTo(2);
        assertThat(summary.get(1).totalCostUsd()).isEqualByComparingTo(BigDecimal.valueOf(2.0).setScale(6));
    }

    private static CallRecordFilter emptyFilter() {
        return new CallRecordFilter(null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
