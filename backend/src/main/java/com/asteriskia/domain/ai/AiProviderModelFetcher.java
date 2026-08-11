package com.asteriskia.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * AiProviderModelFetcher — busca a lista de IDs de modelos disponíveis na API de cada provedor de
 * IA (Gemini, Anthropic, OpenAI, Grok, Perplexity, ElevenLabs, Ollama local), extraído de
 * AiProviderService (fase 12 da refatoração). Devolve apenas IDs crus, sem metadados — o
 * enriquecimento com descrição/tags/capabilities fica em AiProviderService + AiModelCatalog.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiProviderModelFetcher {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

    public List<String> fetchRawIds(String provider, String apiKey) {
        return switch (provider) {
            case "gemini" -> fetchGeminiModels(apiKey);
            case "anthropic" -> fetchAnthropicModels(apiKey);
            case "openai" -> fetchOpenAiModels(apiKey);
            case "grok" -> fetchGrokModels(apiKey);
            case "perplexity" -> fetchPerplexityModels(apiKey);
            case "elevenlabs" -> fetchElevenLabsModels(apiKey);
            case "local" -> fetchOllamaModels();
            default -> List.of();
        };
    }

    /**
     * Busca modelos Gemini e retorna lista completa para STT/LLM/TTS.
     *
     * <p>O Gemini não diferencia STT de LLM via supportedGenerationMethods — ambos usam
     * generateContent. A distinção é feita por convenção de nome: TTS → contém "-tts" STT → todos
     * generateContent exceto -tts, -image, lyria, embedding LLM → todos generateContent exceto
     * -tts, -image, lyria, embedding
     *
     * <p>O pré-filtro por capability em AiProviderService.fetchModels() aplica a regra correta
     * depois.
     */
    private List<String> fetchGeminiModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json =
                    webClientBuilder
                            .build()
                            .get()
                            .uri(
                                    "https://generativelanguage.googleapis.com/v1beta/models?key="
                                            + apiKey
                                            + "&pageSize=100")
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(API_TIMEOUT)
                            .block();
            JsonNode root = objectMapper.readTree(json);
            return StreamSupport.stream(root.path("models").spliterator(), false)
                    .map(n -> n.path("name").asText().replace("models/", ""))
                    .filter(id -> !id.isBlank())
                    // Exclui modelos que não são úteis para STT/LLM/TTS de voz
                    .filter(
                            id -> {
                                String l = id.toLowerCase();
                                return !l.contains("embedding")
                                        && !l.contains("aqa")
                                        && !l.contains("lyria") // geração de música
                                        && !l.contains("-image") // geração de imagens
                                        && !l.startsWith("imagen") // Imagen — geração de imagens
                                        && !l.startsWith("veo") // Veo — geração de vídeo
                                        && !l.contains("robotics") // controle de robôs
                                        && !l.contains("computer-use") // automação de desktop
                                        && !l.contains(
                                                "nano-banana") // experimental não documentado
                                        && !l.contains(
                                                "antigravity") // experimental não documentado
                                        && !l.contains(
                                                "deep-research") // pesquisa longa, não adequado
                                        // para voz
                                        && !l.contains(
                                                "live-translate") // tradução ao vivo, não para URA
                                        && !l.startsWith(
                                                "gemma"); // modelos abertos sem suporte TTS/STT
                                // nativo
                            })
                    .toList();
        } catch (Exception e) {
            log.warn("Erro ao buscar modelos Gemini: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchAnthropicModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json =
                    webClientBuilder
                            .build()
                            .get()
                            .uri("https://api.anthropic.com/v1/models")
                            .header("x-api-key", apiKey)
                            .header("anthropic-version", "2023-06-01")
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(API_TIMEOUT)
                            .block();
            JsonNode root = objectMapper.readTree(json);
            return StreamSupport.stream(root.path("data").spliterator(), false)
                    .map(n -> n.path("id").asText())
                    .filter(id -> !id.isBlank())
                    // Mantém apenas modelos claude-* (exclui legados/outros)
                    .filter(id -> id.startsWith("claude"))
                    .sorted(java.util.Comparator.reverseOrder()) // mais recentes primeiro
                    .toList();
        } catch (Exception e) {
            log.warn("Erro ao buscar modelos Anthropic: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchOpenAiModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json =
                    webClientBuilder
                            .build()
                            .get()
                            .uri("https://api.openai.com/v1/models")
                            .header("Authorization", "Bearer " + apiKey)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(API_TIMEOUT)
                            .block();
            JsonNode root = objectMapper.readTree(json);
            return StreamSupport.stream(root.path("data").spliterator(), false)
                    .map(n -> n.path("id").asText())
                    .filter(id -> !id.isBlank())
                    // Exclui modelos de embedding, imagem, moderação e legados
                    .filter(
                            id ->
                                    !id.contains("embedding")
                                            && !id.contains("dall-e")
                                            && !id.contains("moderat")
                                            && !id.contains("babbage")
                                            && !id.contains("davinci")
                                            && !id.contains("ada")
                                            && !id.contains("curie")
                                            && !id.contains("instruct"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("Erro ao buscar modelos OpenAI: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchGrokModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json =
                    webClientBuilder
                            .build()
                            .get()
                            .uri("https://api.x.ai/v1/models")
                            .header("Authorization", "Bearer " + apiKey)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(API_TIMEOUT)
                            .block();
            JsonNode root = objectMapper.readTree(json);
            return StreamSupport.stream(root.path("data").spliterator(), false)
                    .map(n -> n.path("id").asText())
                    .filter(id -> !id.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("Erro ao buscar modelos Grok: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchPerplexityModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json =
                    webClientBuilder
                            .build()
                            .get()
                            .uri("https://api.perplexity.ai/models")
                            .header("Authorization", "Bearer " + apiKey)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(API_TIMEOUT)
                            .block();
            JsonNode root = objectMapper.readTree(json);
            return StreamSupport.stream(root.path("data").spliterator(), false)
                    .map(n -> n.path("id").asText())
                    .filter(id -> !id.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("Erro ao buscar modelos Perplexity: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchElevenLabsModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json =
                    webClientBuilder
                            .build()
                            .get()
                            .uri("https://api.elevenlabs.io/v1/models")
                            .header("xi-api-key", apiKey)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(API_TIMEOUT)
                            .block();
            JsonNode root = objectMapper.readTree(json);
            List<String> ids = new ArrayList<>();
            if (root.isArray()) {
                root.forEach(
                        n -> {
                            String id = n.path("model_id").asText();
                            if (!id.isBlank()) ids.add(id);
                        });
            }
            return ids;
        } catch (Exception e) {
            log.warn("Erro ao buscar modelos ElevenLabs: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchOllamaModels() {
        try {
            String json =
                    webClientBuilder
                            .build()
                            .get()
                            .uri("http://localhost:11434/api/tags")
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(3))
                            .block();
            JsonNode root = objectMapper.readTree(json);
            return StreamSupport.stream(root.path("models").spliterator(), false)
                    .map(n -> n.path("name").asText())
                    .filter(id -> !id.isBlank())
                    .toList();
        } catch (Exception e) {
            log.debug("Ollama não disponível localmente: {}", e.getMessage());
            return List.of();
        }
    }
}
