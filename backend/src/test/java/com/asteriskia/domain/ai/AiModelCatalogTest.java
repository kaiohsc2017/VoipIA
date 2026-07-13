package com.asteriskia.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AiModelCatalogTest — teste de caracterização (fase 3 da refatoração). Trava o comportamento do
 * catálogo estático de provedores/modelos extraído de AiProviderService.
 */
class AiModelCatalogTest {

    @Test
    void providers_devemCobrirOsSeteProvedoresSuportados() {
        List<String> ids =
                AiModelCatalog.PROVIDERS.stream().map(AiModelCatalog.ProviderDef::id).toList();

        assertThat(ids)
                .containsExactlyInAnyOrder(
                        "gemini",
                        "anthropic",
                        "openai",
                        "grok",
                        "perplexity",
                        "elevenlabs",
                        "local");
    }

    @Test
    void metadataFor_modeloCatalogado_deveRetornarMetadadosCompletos() {
        Optional<AiModelInfo> meta = AiModelCatalog.metadataFor("gemini-2.5-flash");

        assertThat(meta).isPresent();
        assertThat(meta.get().capabilities()).containsExactlyInAnyOrder("STT", "LLM");
        assertThat(meta.get().tags()).contains("speed", "deep");
    }

    @Test
    void metadataFor_modeloTtsCatalogado_deveConterApenasCapabilityTts() {
        Optional<AiModelInfo> meta = AiModelCatalog.metadataFor("gemini-2.5-flash-preview-tts");

        assertThat(meta).isPresent();
        assertThat(meta.get().capabilities()).containsExactly("TTS");
    }

    @Test
    void metadataFor_modeloDesconhecido_deveRetornarVazio() {
        assertThat(AiModelCatalog.metadataFor("modelo-inexistente-xyz")).isEmpty();
    }
}
