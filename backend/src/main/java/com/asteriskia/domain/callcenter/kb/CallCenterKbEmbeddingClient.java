package com.asteriskia.domain.callcenter.kb;

import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * CallCenterKbEmbeddingClient — chama o servidor HTTP interno de embeddings dentro do container
 * {@code insights} ({@code POST /internal/embed}, Fase 25). Nunca exposto fora da rede docker —
 * autenticado com o mesmo esquema de "chave interna" já usado entre os serviços do projeto
 * ({@code X-Internal-Key}).
 */
@Slf4j
@Component
public class CallCenterKbEmbeddingClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;
    private final String internalApiKey;

    public CallCenterKbEmbeddingClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.callcenter.kb.embedding-url}") String embeddingUrl,
            @Value("${app.internal-api-key}") String internalApiKey) {
        this.webClient = webClientBuilder.baseUrl(embeddingUrl).build();
        this.internalApiKey = internalApiKey;
    }

    /** Gera o embedding (384 dimensões) de um texto e devolve já no formato de literal do
     * pgvector ({@code "[0.1,0.2,...]"}), pronto para ser usado num {@code ?::vector} de query
     * nativa — evita depender de um tipo Hibernate customizado para uma única coluna. */
    public String embedAsVectorLiteral(String text) {
        var response =
                webClient
                        .post()
                        .uri("/internal/embed")
                        .header("X-Internal-Key", internalApiKey)
                        .bodyValue(new EmbedRequest(text))
                        .retrieve()
                        .bodyToMono(EmbedResponse.class)
                        .block(REQUEST_TIMEOUT);
        if (response == null || response.vector() == null || response.vector().isEmpty()) {
            throw new IllegalStateException("Servidor de embeddings retornou vetor vazio");
        }
        return toVectorLiteral(response.vector());
    }

    private String toVectorLiteral(List<Double> vector) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(vector.get(i));
        }
        return sb.append(']').toString();
    }

    private record EmbedRequest(String text) {}

    private record EmbedResponse(List<Double> vector) {}
}
