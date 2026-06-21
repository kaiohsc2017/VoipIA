package com.asteriskia.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.stream.StreamSupport;

/**
 * AiProviderService
 *
 * Responsabilidades:
 *   1. Gerenciar API keys dos provedores (CRUD no banco)
 *   2. Buscar modelos disponíveis na API de cada provedor em tempo real
 *   3. Enriquecer cada modelo com descrição + tags legíveis
 *   4. Gerenciar a capability chain (STT/LLM/TTS) — leitura e escrita
 *   5. Expor a chain ativa para o ai-agent via endpoint interno
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProviderService {

    private final AiProviderKeyRepository    keyRepo;
    private final AiCapabilityChainRepository chainRepo;
    private final WebClient.Builder          webClientBuilder;
    private final ObjectMapper               objectMapper;

    // ─── Timeout para chamadas externas ──────────────────────────────────────
    private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

    // ─── Metadados internos dos modelos ──────────────────────────────────────
    // Enriquecem o que a API retorna com descrições e tags legíveis ao usuário.
    private static final Map<String, AiModelInfo> MODEL_METADATA = new HashMap<>();

    static {
        // Gemini STT
        m("gemini-2.0-flash",              "Gemini 2.0 Flash",              "Rápido e preciso, ideal para transcrição em tempo real",          List.of("speed"),        List.of("STT","LLM"));
        m("gemini-1.5-flash",              "Gemini 1.5 Flash",              "Rápido, ótimo custo-benefício",                                   List.of("speed","cost"), List.of("STT","LLM"));
        m("gemini-1.5-pro",                "Gemini 1.5 Pro",                "Alta acurácia em áudio complexo, contexto longo",                 List.of("deep"),         List.of("STT","LLM"));
        m("gemini-2.5-flash-preview",      "Gemini 2.5 Flash Preview",      "Raciocínio rápido com pensamento profundo integrado",             List.of("speed","deep"), List.of("LLM"));
        m("gemini-2.5-flash-preview-tts",  "Gemini 2.5 Flash TTS",          "Voz natural com streaming, excelente suporte a PT-BR",            List.of("voice"),        List.of("TTS"));
        m("gemini-2.0-flash-tts",          "Gemini 2.0 Flash TTS",          "Latência mínima, qualidade de voz adequada para produção",        List.of("speed","voice"),List.of("TTS"));
        // Anthropic
        m("claude-opus-4-5",               "Claude Opus 4.5",               "Raciocínio avançado, contexto de 200K tokens, tarefas complexas",  List.of("deep"),         List.of("LLM"));
        m("claude-sonnet-4-5",             "Claude Sonnet 4.5",             "Equilíbrio entre velocidade e profundidade analítica",             List.of("deep","speed"), List.of("LLM"));
        m("claude-haiku-3-5-20241022",     "Claude Haiku 3.5",              "Resposta rápida, custo reduzido, bom para alto volume",            List.of("speed","cost"), List.of("LLM"));
        m("claude-3-opus-20240229",        "Claude 3 Opus",                 "Análise profunda, raciocínio lógico avançado",                    List.of("deep"),         List.of("LLM"));
        // OpenAI STT
        m("whisper-1",                     "Whisper 1",                     "Alta acurácia, robusto a sotaques e ruído de fundo",              List.of("deep"),         List.of("STT"));
        m("gpt-4o-transcribe",             "GPT-4o Transcribe",             "Transcrição em tempo real com compreensão de contexto",            List.of("speed","deep"), List.of("STT"));
        // OpenAI LLM
        m("gpt-4o",                        "GPT-4o",                        "Raciocínio avançado, multimodal, contexto de 128K tokens",        List.of("deep"),         List.of("LLM"));
        m("gpt-4o-mini",                   "GPT-4o Mini",                   "Rápido, econômico, ideal para produção em alto volume",            List.of("speed","cost"), List.of("LLM"));
        m("gpt-4-turbo",                   "GPT-4 Turbo",                   "Contexto longo, geração precisa e consistente",                    List.of("deep"),         List.of("LLM"));
        m("o1",                            "OpenAI o1",                     "Pensamento profundo passo a passo, resolução lógica complexa",     List.of("deep"),         List.of("LLM"));
        m("o1-mini",                       "OpenAI o1 Mini",                "Raciocínio estruturado com custo reduzido",                        List.of("deep","cost"),  List.of("LLM"));
        // OpenAI TTS
        m("tts-1",                         "TTS-1",                         "Síntese rápida, adequada para alto volume de chamadas",            List.of("speed"),        List.of("TTS"));
        m("tts-1-hd",                      "TTS-1 HD",                      "Máxima qualidade de voz, streaming suave",                        List.of("voice"),        List.of("TTS"));
        m("gpt-4o-mini-tts",               "GPT-4o Mini TTS",               "Voz expressiva com baixa latência, boa naturalidade",             List.of("speed","voice"),List.of("TTS"));
        // Grok
        m("grok-3",                        "Grok 3",                        "Raciocínio profundo, contexto amplo e criatividade",               List.of("deep"),         List.of("LLM"));
        m("grok-3-mini",                   "Grok 3 Mini",                   "Resposta rápida para tarefas objetivas",                           List.of("speed"),        List.of("LLM"));
        m("grok-2",                        "Grok 2",                        "Estável, bom custo-benefício para uso geral",                      List.of("cost"),         List.of("LLM"));
        // Perplexity
        m("sonar-pro",                     "Sonar Pro",                     "Raciocínio com pesquisa web em tempo real, fontes citadas",        List.of("deep"),         List.of("LLM"));
        m("sonar",                         "Sonar",                         "Respostas rápidas com acesso à internet atualizada",               List.of("speed"),        List.of("LLM"));
        m("sonar-reasoning",               "Sonar Reasoning",               "Lógica estruturada combinada com fontes recentes da web",          List.of("deep"),         List.of("LLM"));
        // ElevenLabs
        m("eleven_turbo_v2_5",             "Turbo v2.5",                    "Streaming ultra-rápido, voz natural, latência < 400ms",            List.of("speed","voice"),List.of("TTS"));
        m("eleven_turbo_v2",               "Turbo v2",                      "Rápido com boa expressividade vocal",                              List.of("speed","voice"),List.of("TTS"));
        m("eleven_multilingual_v2",        "Multilingual v2",               "Máxima naturalidade, excelente suporte PT-BR",                    List.of("voice"),        List.of("TTS"));
        m("eleven_flash_v2_5",             "Flash v2.5",                    "Latência mínima para tempo real, boa para URA",                   List.of("speed"),        List.of("TTS"));
        // Local
        m("whisper-large-v3",              "Whisper Large v3",              "Offline, alta acurácia — dados não saem do servidor",              List.of("priv","deep"),  List.of("STT"));
        m("whisper-medium",                "Whisper Medium",                "Offline, boa velocidade, sem custo de API",                        List.of("priv","speed"), List.of("STT"));
        m("llama3.2",                      "Llama 3.2",                     "Leve, rápido, sem custo de API, privado",                          List.of("priv","speed"), List.of("LLM"));
        m("mistral",                       "Mistral",                       "Eficiente, bom raciocínio, completamente local",                   List.of("priv"),         List.of("LLM"));
        m("phi3",                          "Phi-3",                         "Compacto, respostas diretas, baixo consumo de RAM",                List.of("priv","cost"),  List.of("LLM"));
        m("gemma2",                        "Gemma 2",                       "Equilíbrio entre qualidade e uso de recursos",                     List.of("priv"),         List.of("LLM"));
    }

    private static void m(String id, String display, String desc, List<String> tags, List<String> caps) {
        MODEL_METADATA.put(id, new AiModelInfo(id, display, desc, tags, caps));
    }

    // ─── Definição estática dos provedores ────────────────────────────────────
    public record ProviderDef(String id, String name, List<String> capabilities) {}

    public static final List<ProviderDef> PROVIDERS = List.of(
        new ProviderDef("gemini",     "Google Gemini",  List.of("STT","LLM","TTS")),
        new ProviderDef("anthropic",  "Anthropic",      List.of("LLM")),
        new ProviderDef("openai",     "OpenAI",         List.of("STT","LLM","TTS")),
        new ProviderDef("grok",       "Grok (xAI)",     List.of("LLM")),
        new ProviderDef("perplexity", "Perplexity",     List.of("LLM")),
        new ProviderDef("elevenlabs", "ElevenLabs",     List.of("TTS")),
        new ProviderDef("local",      "Local (Ollama)", List.of("STT","LLM"))
    );

    // ─── Keys ────────────────────────────────────────────────────────────────

    public List<AiProviderKey> listProviderKeys() {
        return keyRepo.findAll().stream()
            .map(k -> AiProviderKey.builder()
                .provider(k.getProvider())
                .apiKey(k.getApiKey().isBlank() ? "" : "••••••••")  // máscara
                .isActive(k.getIsActive())
                .updatedAt(k.getUpdatedAt())
                .build())
            .toList();
    }

    @Transactional
    public void saveKey(String provider, String apiKey, String updatedBy) {
        AiProviderKey entity = keyRepo.findById(provider)
            .orElseGet(() -> AiProviderKey.builder().provider(provider).build());
        entity.setApiKey(apiKey);
        entity.setIsActive(true);
        entity.setUpdatedBy(updatedBy);
        keyRepo.save(entity);
        log.info("AI provider key atualizada: {} por {}", provider, updatedBy);
    }

    /** Retorna a key real (não mascarada) — uso interno. */
    public String getRawKey(String provider) {
        return keyRepo.findById(provider).map(AiProviderKey::getApiKey).orElse("");
    }

    // ─── Busca de modelos via API do provedor ─────────────────────────────────

    public List<AiModelInfo> fetchModels(String provider, String capability) {
        String apiKey = getRawKey(provider);
        List<String> rawIds = switch (provider) {
            case "gemini"     -> fetchGeminiModels(apiKey);
            case "anthropic"  -> fetchAnthropicModels(apiKey);
            case "openai"     -> fetchOpenAiModels(apiKey);
            case "grok"       -> fetchGrokModels(apiKey);
            case "perplexity" -> fetchPerplexityModels(apiKey);
            case "elevenlabs" -> fetchElevenLabsModels(apiKey);
            case "local"      -> fetchOllamaModels();
            default           -> List.of();
        };

        return rawIds.stream()
            .map(id -> enrichModel(id, capability))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(m -> scoreModel(m, capability)))
            .toList();
    }

    /** Enriquece um model ID com metadados internos, filtrando por capability. */
    private AiModelInfo enrichModel(String id, String capability) {
        AiModelInfo meta = MODEL_METADATA.get(id);
        if (meta != null) {
            // Só inclui se o modelo suporta a capability pedida
            if (!meta.capabilities().contains(capability)) return null;
            return meta;
        }
        // Modelo desconhecido — inferência pelo nome
        List<String> caps = inferCapabilities(id);
        if (!caps.contains(capability)) return null;
        return new AiModelInfo(id, id, "Modelo disponível na conta", List.of(), caps);
    }

    /** Score para ordenação: modelos primários (mais usados) primeiro. */
    private int scoreModel(AiModelInfo m, String cap) {
        // Ordena: modelos com tags vêm primeiro, depois por nome
        return m.tags().isEmpty() ? 99 : 0;
    }

    private List<String> inferCapabilities(String id) {
        List<String> caps = new ArrayList<>();
        String lower = id.toLowerCase();
        if (lower.contains("tts") || lower.contains("speech") || lower.contains("audio"))      caps.add("TTS");
        if (lower.contains("whisper") || lower.contains("transcri"))                            caps.add("STT");
        if (caps.isEmpty())                                                                      caps.addAll(List.of("LLM"));
        return caps;
    }

    // ─── Chamadas reais às APIs ───────────────────────────────────────────────

    private List<String> fetchGeminiModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json = webClientBuilder.build()
                .get()
                .uri("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(API_TIMEOUT)
                .block();
            JsonNode root = objectMapper.readTree(json);
            return StreamSupport.stream(root.path("models").spliterator(), false)
                .map(n -> n.path("name").asText().replace("models/",""))
                .filter(id -> !id.isBlank())
                .toList();
        } catch (Exception e) {
            log.warn("Erro ao buscar modelos Gemini: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchAnthropicModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json = webClientBuilder.build()
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
                .toList();
        } catch (Exception e) {
            log.warn("Erro ao buscar modelos Anthropic: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchOpenAiModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json = webClientBuilder.build()
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
                .toList();
        } catch (Exception e) {
            log.warn("Erro ao buscar modelos OpenAI: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchGrokModels(String apiKey) {
        if (apiKey.isBlank()) return List.of();
        try {
            String json = webClientBuilder.build()
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
            String json = webClientBuilder.build()
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
            String json = webClientBuilder.build()
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
                root.forEach(n -> {
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
            String json = webClientBuilder.build()
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

    // ─── Chain ────────────────────────────────────────────────────────────────

    public List<AiCapabilityChain> getChain(String capability) {
        return chainRepo.findByCapabilityOrderByPriorityAsc(capability);
    }

    public List<AiCapabilityChain> getAllChains() {
        List<AiCapabilityChain> result = new ArrayList<>();
        for (String cap : List.of("STT","LLM","TTS")) {
            result.addAll(chainRepo.findByCapabilityOrderByPriorityAsc(cap));
        }
        return result;
    }

    @Transactional
    public void saveChain(String capability, List<ChainEntryRequest> entries, String updatedBy) {
        chainRepo.deleteByCapability(capability);
        for (int i = 0; i < entries.size(); i++) {
            ChainEntryRequest e = entries.get(i);
            chainRepo.save(AiCapabilityChain.builder()
                .capability(capability)
                .priority(i + 1)
                .provider(e.provider())
                .modelId(e.modelId())
                .isEnabled(true)
                .updatedBy(updatedBy)
                .build());
        }
        log.info("Chain {} salva com {} entradas por {}", capability, entries.size(), updatedBy);
    }

    public record ChainEntryRequest(String provider, String modelId) {}
}
