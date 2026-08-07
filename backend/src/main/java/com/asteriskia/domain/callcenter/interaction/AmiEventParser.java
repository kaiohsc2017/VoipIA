package com.asteriskia.domain.callcenter.interaction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AmiEventParser — parsing puro de um bloco de evento AMI (linhas "Chave: Valor" lidas por
 * {@link com.asteriskia.integration.ami.AmiSession#readBlock()}) para um mapa chave/valor.
 * Extraído como classe própria (sem dependência de socket) para ser testável sem um Asterisk
 * real — ver {@link CallCenterAmiEventListener} para o consumo em produção.
 */
final class AmiEventParser {

    private AmiEventParser() {}

    static Map<String, String> parse(String rawBlock) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : rawBlock.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int sep = line.indexOf(':');
            if (sep < 0) {
                continue;
            }
            fields.put(line.substring(0, sep).trim(), line.substring(sep + 1).trim());
        }
        return fields;
    }
}
