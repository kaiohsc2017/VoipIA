package com.asteriskia.domain.ai;

import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AiProviderService
 *
 * <p>Responsabilidades: 1. Gerenciar API keys dos provedores (CRUD no banco) 2. Orquestrar a busca
 * de modelos disponíveis por provedor (delegada a {@link AiProviderModelFetcher}, extraído na fase
 * 12 da refatoração) 3. Enriquecer cada modelo com descrição + tags legíveis (catálogo em
 * AiModelCatalog) 4. Gerenciar a capability chain (STT/LLM/TTS) — leitura e escrita 5. Expor a
 * chain ativa para o ai-agent via endpoint interno
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProviderService {

    private final AiProviderKeyRepository keyRepo;
    private final AiCapabilityChainRepository chainRepo;
    private final AiProviderModelFetcher modelFetcher;

    // ─── Keys ────────────────────────────────────────────────────────────────

    public List<AiProviderKey> listProviderKeys() {
        return keyRepo.findAll().stream()
                .map(
                        k ->
                                AiProviderKey.builder()
                                        .provider(k.getProvider())
                                        .apiKey(
                                                k.getApiKey().isBlank()
                                                        ? ""
                                                        : "••••••••") // máscara
                                        .isActive(k.getIsActive())
                                        .updatedAt(k.getUpdatedAt())
                                        .build())
                .toList();
    }

    @Transactional
    public void saveKey(String provider, String apiKey, String updatedBy) {
        AiProviderKey entity =
                keyRepo.findById(provider)
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
        List<String> rawIds = modelFetcher.fetchRawIds(provider, apiKey);

        return rawIds.stream()
                // Pré-filtra por capability: modelos catalogados usam metadados,
                // modelos desconhecidos usam inferência pelo nome
                .filter(
                        id -> {
                            Optional<AiModelInfo> meta = AiModelCatalog.metadataFor(id);
                            if (meta.isPresent())
                                return meta.get().capabilities().contains(capability);
                            // Para modelos não catalogados, infere pela convenção de nome
                            List<String> inferred = inferCapabilitiesFromName(id);
                            return inferred.contains(capability);
                        })
                .map(id -> enrichModel(id, capability))
                .filter(Objects::nonNull)
                // Catalogados (com tags) primeiro, depois por nome
                .sorted(
                        Comparator.comparingInt((AiModelInfo m) -> scoreModel(m, capability))
                                .thenComparing(AiModelInfo::id))
                .toList();
    }

    /**
     * Enriquece um model ID com metadados do catálogo. Para modelos catalogados, filtra pela
     * capability. Para modelos desconhecidos vindos da API, inclui diretamente — a filtragem por
     * capability já foi feita pelo fetchXxxModels antes de chamar este método.
     */
    private AiModelInfo enrichModel(String id, String capability) {
        Optional<AiModelInfo> meta = AiModelCatalog.metadataFor(id);
        if (meta.isPresent()) {
            if (!meta.get().capabilities().contains(capability)) return null;
            return meta.get();
        }
        // Modelo desconhecido — já foi pré-filtrado pela API, inclui sem descartar
        return new AiModelInfo(
                id, id, "Modelo disponível na conta", List.of(), List.of(capability));
    }

    /** Score para ordenação: modelos catalogados (com tags/descrição) primeiro. */
    private int scoreModel(AiModelInfo m, String cap) {
        return m.tags().isEmpty() ? 99 : 0;
    }

    /**
     * Infere as capabilities de um modelo pelo seu nome.
     *
     * <p>Regras (baseadas nas convenções reais das APIs): "-tts" → TTS apenas (Gemini, OpenAI)
     * "whisper" → STT apenas (OpenAI) "tts-1", "tts-1-hd" → TTS (OpenAI) "eleven_*" → TTS
     * (ElevenLabs) demais → STT + LLM (Gemini usa generateContent para ambos)
     */
    private List<String> inferCapabilitiesFromName(String id) {
        String lower = id.toLowerCase();

        // TTS explícito pelo nome
        if (lower.contains("-tts") || lower.startsWith("tts-") || lower.startsWith("eleven_")) {
            return List.of("TTS");
        }
        // STT explícito pelo nome
        if (lower.contains("whisper")) {
            return List.of("STT");
        }
        // Modelos de geração de texto gerais (Gemini, GPT, Claude…)
        // servem tanto para STT (via áudio inline) quanto LLM
        return List.of("STT", "LLM");
    }

    // ─── Chain ────────────────────────────────────────────────────────────────

    public List<AiCapabilityChain> getChain(String capability) {
        return chainRepo.findByCapabilityOrderByPriorityAsc(capability);
    }

    public List<AiCapabilityChain> getAllChains() {
        List<AiCapabilityChain> result = new ArrayList<>();
        for (String cap : List.of("STT", "LLM", "TTS")) {
            result.addAll(chainRepo.findByCapabilityOrderByPriorityAsc(cap));
        }
        return result;
    }

    @Transactional
    public void saveChain(String capability, List<ChainEntryRequest> entries, String updatedBy) {
        chainRepo.deleteByCapability(capability);
        for (int i = 0; i < entries.size(); i++) {
            ChainEntryRequest e = entries.get(i);
            chainRepo.save(
                    AiCapabilityChain.builder()
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
