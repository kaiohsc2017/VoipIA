package com.asteriskia.domain.masterdata;

import java.text.Normalizer;

/**
 * NameNormalizer — normalização de nomes (remove acentos, caixa e espaços
 * duplicados) para comparação tolerante a variações de digitação.
 *
 * Extraído de MasterDataController (usado originalmente só na importação CSV
 * de testes de conectividade) para ser reaproveitado também na resolução de
 * Cliente por nome no cadastro do DATACENTER.
 */
public final class NameNormalizer {

    private NameNormalizer() {}

    public static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .replaceAll("\\s+", " ");
    }
}
