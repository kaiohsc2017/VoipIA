package com.asteriskia.domain.settings;

/**
 * EnvEntry — representa uma linha do arquivo .env.
 *
 * Tipos:
 *  - FIELD   → linha com chave=valor
 *  - COMMENT → linha que começa com #
 *  - BLANK   → linha em branco
 */
public record EnvEntry(
        Type type,
        String key,
        String value,
        boolean isSecret
) {

    public enum Type { FIELD, COMMENT, BLANK }

    public static EnvEntry field(String key, String value, boolean isSecret) {
        return new EnvEntry(Type.FIELD, key, value, isSecret);
    }

    public static EnvEntry comment(String text) {
        return new EnvEntry(Type.COMMENT, null, text, false);
    }

    public static EnvEntry blank() {
        return new EnvEntry(Type.BLANK, null, null, false);
    }
}
