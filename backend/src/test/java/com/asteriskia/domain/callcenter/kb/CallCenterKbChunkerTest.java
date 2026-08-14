package com.asteriskia.domain.callcenter.kb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * CallCenterKbChunkerTest — cobre os 3 cenários centrais do chunker: texto vazio (nenhum chunk),
 * parágrafos curtos (1 chunk por parágrafo, sem quebrar no meio) e um parágrafo maior que o
 * limite (quebrado por espaço, nunca no meio de uma palavra).
 */
class CallCenterKbChunkerTest {

    @Test
    void chunk_textoNuloOuVazio_retornaListaVazia() {
        assertThat(CallCenterKbChunker.chunk(null)).isEmpty();
        assertThat(CallCenterKbChunker.chunk("   ")).isEmpty();
    }

    @Test
    void chunk_paragrafosCurtos_umChunkPorParagrafo() {
        var text = "Primeiro parágrafo curto.\n\nSegundo parágrafo curto.";

        var chunks = CallCenterKbChunker.chunk(text);

        assertThat(chunks).containsExactly("Primeiro parágrafo curto.", "Segundo parágrafo curto.");
    }

    @Test
    void chunk_paragrafoMaiorQueLimite_quebraPorEspacoSemCortarPalavra() {
        var word = "palavra ";
        var longParagraph = word.repeat(300).trim();

        var chunks = CallCenterKbChunker.chunk(longParagraph);

        assertThat(chunks).hasSizeGreaterThan(1);
        chunks.forEach(c -> assertThat(c).doesNotStartWith(" ").doesNotEndWith(" "));
        assertThat(String.join(" ", chunks).replaceAll("\\s+", " ")).isEqualTo(longParagraph);
    }
}
