package com.asteriskia.domain.callcenter.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.asteriskia.domain.ai.AiModelPricingRepository;
import com.asteriskia.domain.ai.AiProviderService;
import com.asteriskia.integration.ad.AdUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ContactProfileGeneratorTest — cobre só o clamp de {@code riscoEscalonamento} (Fase 16.2, lição
 * do overflow numérico de {@code call_insights.aderencia_script} da Fase 8) e a defesa contra
 * campos nulos vindos do LLM. A chamada real ao Gemini (WebClient) não é testada aqui — mesma
 * decisão já tomada para {@code CallCenterKbAnswerService}/{@code
 * CallCenterNpsTranscriptionScheduler}, cobertos só por validação manual em produção.
 */
class ContactProfileGeneratorTest {

    private final ContactProfileGenerator generator = new ContactProfileGenerator(
            mock(CcContactProfileRepository.class),
            mock(AdUserRepository.class),
            mock(AiProviderService.class),
            mock(AiModelPricingRepository.class),
            new ObjectMapper(),
            mock(WebClient.Builder.class));

    @Test
    @DisplayName("riscoEscalonamento acima de 1 é clampado para 1")
    void clampsRiskAboveOne() {
        var raw = new ContactProfileContent("r", "s", List.of("t"), BigDecimal.valueOf(4.2), List.of());

        var clamped = generator.clamp(raw);

        assertThat(clamped.riscoEscalonamento()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("riscoEscalonamento negativo é clampado para 0")
    void clampsNegativeRiskToZero() {
        var raw = new ContactProfileContent("r", "s", List.of(), BigDecimal.valueOf(-0.5), List.of());

        var clamped = generator.clamp(raw);

        assertThat(clamped.riscoEscalonamento()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("riscoEscalonamento nulo vira zero, e listas/strings nulas nunca quebram a UI")
    void nullFields_becomeSafeDefaults() {
        var raw = new ContactProfileContent(null, null, null, null, null);

        var clamped = generator.clamp(raw);

        assertThat(clamped.riscoEscalonamento()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(clamped.resumoPerfil()).isEmpty();
        assertThat(clamped.sentimentoHistorico()).isEmpty();
        assertThat(clamped.temasRecorrentes()).isEmpty();
        assertThat(clamped.acoesSugeridas()).isEmpty();
    }

    @Test
    @DisplayName("valor dentro da faixa [0,1] é preservado")
    void withinRange_isPreserved() {
        var raw = new ContactProfileContent("r", "s", List.of(), BigDecimal.valueOf(0.42), List.of());

        var clamped = generator.clamp(raw);

        assertThat(clamped.riscoEscalonamento()).isEqualByComparingTo(BigDecimal.valueOf(0.42));
    }
}
