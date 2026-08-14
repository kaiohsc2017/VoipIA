package com.asteriskia.domain.callcenter.reports;

/**
 * AniNormalizer — normaliza ANI/CallerID e {@code customerRef} de chat para uma chave de
 * identidade estável (Fase 27, "Perfil do cliente"). Heurística, não uma solução completa: o
 * plano prevê identidade definitiva via {@code resolved_ad_sam} (Fase 14, "Identidade do
 * contato/screen pop"), que ainda não existe no código — enquanto essa fase não for entregue,
 * ANI normalizado é o único identificador disponível (item 9 da tabela de recomendações do
 * plano revisado).
 *
 * <p>Regras aplicadas, nesta ordem: (1) remove tudo que não é dígito; (2) remove o código do
 * país "55" quando o número já tem DDD+9º dígito+8 dígitos (13+); (3) insere o 9º dígito em
 * número de celular de 10 dígitos (DDD + 8 dígitos) sem ele. Ramais internos (poucos dígitos,
 * ex: "1001") não batem em nenhuma das regras de telefone e retornam inalterados — dedup entre
 * ramais funciona porque o valor já é curto e estável.
 */
public final class AniNormalizer {

    private static final int COUNTRY_CODE_MIN_LENGTH = 12;
    private static final int LANDLINE_FORMAT_LENGTH = 10;
    private static final char MOBILE_PREFIX_THRESHOLD = '6';

    private AniNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.length() >= COUNTRY_CODE_MIN_LENGTH && digits.startsWith("55")) {
            digits = digits.substring(2);
        }
        if (digits.length() == LANDLINE_FORMAT_LENGTH && digits.charAt(2) >= MOBILE_PREFIX_THRESHOLD) {
            digits = digits.substring(0, 2) + "9" + digits.substring(2);
        }
        return digits;
    }
}
