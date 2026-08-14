package com.asteriskia.domain.callcenter.cobrowsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cobre o mascaramento server-side de CPF/cartão/telefone em texto livre dentro de eventos
 * rrweb (Fase 17b) — heurística portada de {@code insights/src/masking.py}.
 */
class CobrowseEventSanitizerTest {

    private final CobrowseEventSanitizer sanitizer = new CobrowseEventSanitizer();

    @Test
    @DisplayName("mascara CPF com pontuação")
    void maskText_cpfWithPunctuation_masked() {
        String result = sanitizer.maskText("Meu CPF é 123.456.789-01, pode anotar");
        assertThat(result).contains("[CPF MASCARADO]").doesNotContain("123.456.789-01");
    }

    @Test
    @DisplayName("mascara CPF ditado com espaço em vez de pontuação")
    void maskText_cpfWithSpaces_masked() {
        String result = sanitizer.maskText("123 456 789 01");
        assertThat(result).isEqualTo("[CPF MASCARADO]");
    }

    @Test
    @DisplayName("mascara número de cartão de crédito")
    void maskText_creditCard_masked() {
        String result = sanitizer.maskText("Cartão 4111 1111 1111 1111 vence em 12/28");
        assertThat(result).contains("[CARTÃO MASCARADO]").doesNotContain("4111 1111 1111 1111");
    }

    @Test
    @DisplayName("mascara telefone com DDD entre parênteses")
    void maskText_phoneWithAreaCode_masked() {
        String result = sanitizer.maskText("Me liga no (11) 98765-4321 por favor");
        assertThat(result).contains("[TELEFONE MASCARADO]").doesNotContain("98765-4321");
    }

    @Test
    @DisplayName("texto sem dado sensível permanece inalterado")
    void maskText_noSensitiveData_unchanged() {
        String text = "Olá, tudo bem? Preciso de ajuda com meu pedido.";
        assertThat(sanitizer.maskText(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("null e vazio são tratados sem lançar")
    void maskText_nullOrBlank_returnsAsIs() {
        assertThat(sanitizer.maskText(null)).isNull();
        assertThat(sanitizer.maskText("")).isEmpty();
    }

    @Test
    @DisplayName("sanitizeEvents mascara recursivamente strings dentro de Map/List aninhados")
    void sanitizeEvents_nestedStructure_masksAllStrings() {
        List<Map<String, Object>> events = List.of(
                Map.of("type", 3, "data", Map.of(
                        "text", "Meu CPF é 123.456.789-01",
                        "nested", List.of(Map.of("value", "cartão 4111111111111111")))));

        List<Map<String, Object>> result = sanitizer.sanitizeEvents(events);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(0).get("data");
        assertThat((String) data.get("text")).contains("[CPF MASCARADO]");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nested = (List<Map<String, Object>>) data.get("nested");
        assertThat((String) nested.get(0).get("value")).contains("[CARTÃO MASCARADO]");
    }

    @Test
    @DisplayName("sanitizeEvents com lista nula retorna lista vazia sem lançar")
    void sanitizeEvents_nullList_returnsEmptyList() {
        assertThat(sanitizer.sanitizeEvents(null)).isEmpty();
    }

    @Test
    @DisplayName("valores não-string (números, booleanos) permanecem intactos")
    void sanitizeEvents_nonStringValues_unchanged() {
        List<Map<String, Object>> events = List.of(Map.of("type", 2, "timestamp", 123456789L, "flag", true));

        List<Map<String, Object>> result = sanitizer.sanitizeEvents(events);

        assertThat(result.get(0).get("type")).isEqualTo(2);
        assertThat(result.get(0).get("timestamp")).isEqualTo(123456789L);
        assertThat(result.get(0).get("flag")).isEqualTo(true);
    }
}
