package com.asteriskia.domain.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.ai.AiModelPricing;
import com.asteriskia.domain.ai.AiModelPricingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
 * InsightsCostServiceTest — cobre a regressão do "Custo IA Acumulado (Mês)" zerado:
 * summarizeByMonth() deve agrupar por processedAt (quando o custo foi de fato incorrido),
 * não por callStarttime (data da gravação original, nula em uploads manuais e potencialmente
 * de um mês anterior ao do processamento em chamadas Verint).
 */
@ExtendWith(MockitoExtension.class)
class InsightsCostServiceTest {

    @Mock private CallAudioFileRepository audioFileRepository;
    @Mock private AiModelPricingRepository pricingRepository;

    private InsightsCostService service;

    @BeforeEach
    void setUp() {
        service = new InsightsCostService(audioFileRepository, pricingRepository);
    }

    private static AiModelPricing pricing(String modelId, double in, double out) {
        return AiModelPricing.builder()
                .modelId(modelId)
                .provider("gemini")
                .pricePerMillionInputUsd(BigDecimal.valueOf(in))
                .pricePerMillionOutputUsd(BigDecimal.valueOf(out))
                .build();
    }

    private static CallAudioFile audioFile(
            LocalDateTime callStarttime,
            LocalDateTime processedAt,
            String source,
            int llmTokensIn,
            String llmModel) {
        return CallAudioFile.builder()
                .id(1L)
                .callRef("ref-" + System.identityHashCode(callStarttime))
                .wavPath("/opt/audio/x.wav")
                .callStarttime(callStarttime)
                .processedAt(processedAt)
                .source(source)
                .llmTokensIn(llmTokensIn)
                .llmTokensOut(0)
                .llmModel(llmModel)
                .build();
    }

    @Test
    void findCosts_calculaCustoAPartirDoPrecoPorMilhaoDeTokens() {
        CallAudioFile record = audioFile(
                LocalDateTime.now(), LocalDateTime.now(), "verint", 1_000_000, "gemini-2.5-flash");
        when(audioFileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(pricingRepository.findAll()).thenReturn(List.of(pricing("gemini-2.5-flash", 1.0, 0.0)));

        Page<InsightCostView> result = service.findCosts(emptyFilter(), Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).estimatedCostUsd())
                .isEqualByComparingTo(BigDecimal.valueOf(1.0).setScale(6));
    }

    @Test
    void summarizeByMonth_agrupaPorMesDeProcessamento_naoPorMesDaGravacao() {
        // Gravado em junho, processado (custo incorrido) em julho — deve entrar no bucket de julho.
        CallAudioFile verintDefasado = audioFile(
                LocalDateTime.of(2026, 6, 15, 12, 0),
                LocalDateTime.of(2026, 7, 17, 10, 0),
                "verint", 1_000_000, "gemini-2.5-flash");
        when(audioFileRepository.findAll(any(Specification.class))).thenReturn(List.of(verintDefasado));
        when(pricingRepository.findAll()).thenReturn(List.of(pricing("gemini-2.5-flash", 1.0, 0.0)));

        List<InsightMonthlyCostSummary> summary = service.summarizeByMonth(emptyFilter());

        assertThat(summary).hasSize(1);
        assertThat(summary.get(0).month()).isEqualTo("2026-07");
        assertThat(summary.get(0).totalCostUsd()).isEqualByComparingTo(BigDecimal.valueOf(1.0).setScale(6));
    }

    @Test
    void summarizeByMonth_uploadSemCallStarttime_aindaEntraNoAgrupamentoPorProcessedAt() {
        // Upload manual do portal do supervisor: sem metadado de call_starttime da Verint.
        CallAudioFile upload = audioFile(
                null, LocalDateTime.of(2026, 7, 20, 22, 58), "upload", 1_000_000, "gemini-2.5-flash");
        when(audioFileRepository.findAll(any(Specification.class))).thenReturn(List.of(upload));
        when(pricingRepository.findAll()).thenReturn(List.of(pricing("gemini-2.5-flash", 1.0, 0.0)));

        List<InsightMonthlyCostSummary> summary = service.summarizeByMonth(emptyFilter());

        assertThat(summary).hasSize(1);
        assertThat(summary.get(0).month()).isEqualTo("2026-07");
        assertThat(summary.get(0).callCount()).isEqualTo(1);
    }

    @Test
    void summarizeByMonth_semProcessedAt_ficaDeForaDoAgrupamento() {
        CallAudioFile pendente = audioFile(
                LocalDateTime.of(2026, 7, 1, 0, 0), null, "upload", 0, null);
        when(audioFileRepository.findAll(any(Specification.class))).thenReturn(List.of(pendente));
        when(pricingRepository.findAll()).thenReturn(List.of());

        List<InsightMonthlyCostSummary> summary = service.summarizeByMonth(emptyFilter());

        assertThat(summary).isEmpty();
    }

    private static InsightsCostFilter emptyFilter() {
        return new InsightsCostFilter(null, null, null, null, null);
    }
}
