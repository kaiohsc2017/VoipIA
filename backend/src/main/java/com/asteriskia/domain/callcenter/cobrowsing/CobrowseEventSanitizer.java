package com.asteriskia.domain.callcenter.cobrowsing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * CobrowseEventSanitizer — defesa em profundidade sobre os eventos rrweb já recebidos do widget
 * (Fase 17b). O mascaramento client-side (rrweb {@code maskAllInputs}/{@code maskInputOptions})
 * cobre os campos de formulário, mas texto livre digitado fora de um input (ex: texto solto numa
 * página, copiado/colado) pode carregar CPF/cartão/telefone mesmo assim — este sanitizador roda
 * sobre QUALQUER string do payload antes de persistir, sem exceção de campo.
 *
 * <p>Heurística de regex portada de {@code insights/src/masking.py} (projeto irmão em Python,
 * mesmo pipeline de mascaramento usado em voz) — só regex puro, sem chamada de IA, mesma ordem
 * (cartão antes de CPF/telefone, pois um número de cartão parcialmente mascarado por um padrão
 * mais curto seria pior que não mascarar nada).
 */
@Component
public class CobrowseEventSanitizer {

    // Cartão de crédito: 13-19 dígitos, aceitando espaço/hífen como separador a cada bloco —
    // checado ANTES de CPF/telefone pra não ser mascarado só parcialmente por um padrão mais curto.
    private static final Pattern CARD = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");

    // CPF: 000.000.000-00 / 000 000 000 00 / 00000000000.
    private static final Pattern CPF = Pattern.compile("\\b\\d{3}[ .]?\\d{3}[ .]?\\d{3}[ -]?\\d{2}\\b");

    // Telefone BR: (00) 00000-0000 / 00 00000-0000 / 0000000000 / 00987654321 — checado por
    // último (mais genérico), depois que cartão/CPF já consumiram os padrões mais específicos.
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(?:\\(\\d{2}\\)\\s?|\\d{2}[ -]?)?\\d{4,5}[ -]?\\d{4}\\b");

    /** Mascara CPF/cartão/telefone em texto livre, preservando o restante. */
    public String maskText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = CARD.matcher(text).replaceAll("[CARTÃO MASCARADO]");
        masked = CPF.matcher(masked).replaceAll("[CPF MASCARADO]");
        masked = PHONE.matcher(masked).replaceAll("[TELEFONE MASCARADO]");
        return masked;
    }

    /**
     * Aplica {@link #maskText(String)} recursivamente em todo valor de string dentro da lista de
     * eventos rrweb (estrutura arbitrária de Map/List vinda da desserialização JSON) — nunca
     * confia que o client-side já mascarou tudo.
     */
    public List<Map<String, Object>> sanitizeEvents(List<Map<String, Object>> events) {
        if (events == null) {
            return List.of();
        }
        return events.stream().map(this::sanitizeMap).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMap(Map<String, Object> map) {
        if (map == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(key, sanitizeValue(value)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value) {
        if (value instanceof String s) {
            return maskText(s);
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sanitizeValue).toList();
        }
        return value;
    }
}
