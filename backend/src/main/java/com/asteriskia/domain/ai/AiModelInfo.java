package com.asteriskia.domain.ai;

import java.util.List;

/**
 * Metadados de um modelo retornados ao frontend.
 * Combina o que a API do provedor retorna com descrições internas.
 */
public record AiModelInfo(
    String id,
    String displayName,
    String description,
    List<String> tags,       // speed | deep | voice | cost | priv
    List<String> capabilities // STT | LLM | TTS
) {}
