package com.asteriskia.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * AiProviderModelFetcherTest — teste de caracterização (fase 12 da refatoração). Cobre o
 * comportamento extraído de AiProviderService: API key em branco não dispara chamada de rede,
 * provider desconhecido devolve lista vazia, e o fetch do Ollama local tolera o servidor
 * indisponível.
 */
class AiProviderModelFetcherTest {

    private AiProviderModelFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new AiProviderModelFetcher(WebClient.builder(), new ObjectMapper());
    }

    @Test
    void fetchRawIds_providerDesconhecido_devolveListaVazia() {
        assertThat(fetcher.fetchRawIds("provider-inexistente", "qualquer-key")).isEmpty();
    }

    @Test
    void fetchRawIds_geminiComApiKeyEmBranco_devolveListaVaziaSemChamarRede() {
        assertThat(fetcher.fetchRawIds("gemini", "")).isEmpty();
    }

    @Test
    void fetchRawIds_anthropicComApiKeyEmBranco_devolveListaVaziaSemChamarRede() {
        assertThat(fetcher.fetchRawIds("anthropic", "")).isEmpty();
    }

    @Test
    void fetchRawIds_openaiComApiKeyEmBranco_devolveListaVaziaSemChamarRede() {
        assertThat(fetcher.fetchRawIds("openai", "")).isEmpty();
    }

    @Test
    void fetchRawIds_grokComApiKeyEmBranco_devolveListaVaziaSemChamarRede() {
        assertThat(fetcher.fetchRawIds("grok", "")).isEmpty();
    }

    @Test
    void fetchRawIds_perplexityComApiKeyEmBranco_devolveListaVaziaSemChamarRede() {
        assertThat(fetcher.fetchRawIds("perplexity", "")).isEmpty();
    }

    @Test
    void fetchRawIds_elevenlabsComApiKeyEmBranco_devolveListaVaziaSemChamarRede() {
        assertThat(fetcher.fetchRawIds("elevenlabs", "")).isEmpty();
    }

    @Test
    void fetchRawIds_local_naoQuebraQuandoOllamaIndisponivel() {
        // Sem Ollama rodando em localhost:11434 neste ambiente — deve degradar para lista vazia,
        // nunca lançar exceção.
        assertThat(fetcher.fetchRawIds("local", "")).isEmpty();
    }
}
