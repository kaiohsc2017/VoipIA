package com.asteriskia.domain.callcenter.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * CallCenterKbChunker — divide o corpo de um artigo/fonte em trechos de tamanho limitado antes
 * de gerar o embedding de cada um (Fase 25). Quebra por parágrafo em primeiro lugar (preserva
 * unidades de sentido); um parágrafo maior que {@code MAX_CHUNK_CHARS} é quebrado por espaço,
 * nunca no meio de uma palavra. Sem dependência de biblioteca de NLP — suficiente para o volume
 * de artigos desta base de conhecimento própria.
 */
final class CallCenterKbChunker {

    private static final int MAX_CHUNK_CHARS = 1200;

    private CallCenterKbChunker() {}

    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        for (String paragraph : text.split("\\n{2,}")) {
            var trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= MAX_CHUNK_CHARS) {
                chunks.add(trimmed);
                continue;
            }
            chunks.addAll(splitLong(trimmed));
        }
        return chunks;
    }

    private static List<String> splitLong(String paragraph) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : paragraph.split("\\s+")) {
            if (current.length() + word.length() + 1 > MAX_CHUNK_CHARS && !current.isEmpty()) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }
}
