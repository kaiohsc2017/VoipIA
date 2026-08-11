package com.asteriskia.domain.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AiModelCatalog — catálogo estático de provedores e modelos de IA suportados, extraído de
 * AiProviderService (fase 3 da refatoração). Metadados legíveis (nome de exibição, descrição, tags)
 * usados para enriquecer os modelos que a API de cada provedor retorna em tempo real — não tem
 * nenhuma dependência de repositório/rede, só dados estáticos e lookup.
 */
public final class AiModelCatalog {

    private AiModelCatalog() {}

    /** Definição estática de um provedor suportado. */
    public record ProviderDef(String id, String name, List<String> capabilities) {}

    public static final List<ProviderDef> PROVIDERS =
            List.of(
                    new ProviderDef("gemini", "Google Gemini", List.of("STT", "LLM", "TTS")),
                    new ProviderDef("anthropic", "Anthropic", List.of("LLM")),
                    new ProviderDef("openai", "OpenAI", List.of("STT", "LLM", "TTS")),
                    new ProviderDef("grok", "Grok (xAI)", List.of("LLM")),
                    new ProviderDef("perplexity", "Perplexity", List.of("LLM")),
                    new ProviderDef("elevenlabs", "ElevenLabs", List.of("TTS")),
                    new ProviderDef("local", "Local (Ollama)", List.of("STT", "LLM")));

    private static final Map<String, AiModelInfo> MODEL_METADATA = new HashMap<>();

    static {
        // Gemini STT
        m(
                "gemini-2.0-flash",
                "Gemini 2.0 Flash",
                "Rápido e preciso, ideal para transcrição em tempo real",
                List.of("speed"),
                List.of("STT", "LLM"));
        m(
                "gemini-1.5-flash",
                "Gemini 1.5 Flash",
                "Rápido, ótimo custo-benefício",
                List.of("speed", "cost"),
                List.of("STT", "LLM"));
        m(
                "gemini-1.5-pro",
                "Gemini 1.5 Pro",
                "Alta acurácia em áudio complexo, contexto longo",
                List.of("deep"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.5-flash-preview",
                "Gemini 2.5 Flash Preview",
                "Raciocínio rápido com pensamento profundo integrado",
                List.of("speed", "deep"),
                List.of("LLM"));
        m(
                "gemini-2.5-flash-preview-tts",
                "Gemini 2.5 Flash TTS",
                "Voz natural com streaming, excelente suporte a PT-BR",
                List.of("voice"),
                List.of("TTS"));
        m(
                "gemini-2.0-flash-tts",
                "Gemini 2.0 Flash TTS",
                "Latência mínima, qualidade de voz adequada para produção",
                List.of("speed", "voice"),
                List.of("TTS"));
        // Novos modelos Gemini 2.5 / 3.x
        m(
                "gemini-2.5-flash",
                "Gemini 2.5 Flash",
                "Raciocínio rápido com pensamento profundo integrado",
                List.of("speed", "deep"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.5-pro",
                "Gemini 2.5 Pro",
                "Máxima capacidade, contexto longo, análise avançada",
                List.of("deep"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.5-flash-lite",
                "Gemini 2.5 Flash Lite",
                "Ultra-rápido e econômico para alto volume",
                List.of("speed", "cost"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.0-flash-lite",
                "Gemini 2.0 Flash Lite",
                "Versão leve do Flash, menor custo por token",
                List.of("speed", "cost"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.5-pro-preview-tts",
                "Gemini 2.5 Pro TTS",
                "Voz de alta qualidade com entonação natural avançada",
                List.of("voice", "deep"),
                List.of("TTS"));
        m(
                "gemini-3.1-flash-tts-preview",
                "Gemini 3.1 Flash TTS",
                "TTS de próxima geração, streaming ultra-rápido",
                List.of("speed", "voice"),
                List.of("TTS"));
        m(
                "gemini-3-flash-preview",
                "Gemini 3 Flash Preview",
                "Próxima geração — resposta rápida e raciocínio melhorado",
                List.of("speed", "deep"),
                List.of("STT", "LLM"));
        m(
                "gemini-3-pro-preview",
                "Gemini 3 Pro Preview",
                "Próxima geração — máxima capacidade e contexto expandido",
                List.of("deep"),
                List.of("STT", "LLM"));
        m(
                "gemini-3.5-flash",
                "Gemini 3.5 Flash",
                "Raciocínio avançado com velocidade de Flash",
                List.of("speed", "deep"),
                List.of("STT", "LLM"));
        m(
                "gemini-3.1-flash-lite",
                "Gemini 3.1 Flash Lite",
                "Compacto e econômico para alto volume de transcrições",
                List.of("speed", "cost"),
                List.of("STT", "LLM"));
        m(
                "gemini-3.1-pro-preview",
                "Gemini 3.1 Pro Preview",
                "Análise profunda, ideal para respostas elaboradas",
                List.of("deep"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.5-flash-native-audio-latest",
                "Gemini 2.5 Flash Audio (latest)",
                "Otimizado para áudio nativo — melhor acurácia STT em tempo real",
                List.of("speed", "deep"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.5-flash-native-audio-preview-12-2025",
                "Gemini 2.5 Flash Audio (dez/25)",
                "Transcrição nativa de áudio, excelente precisão em PT-BR",
                List.of("speed"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.5-flash-native-audio-preview-09-2025",
                "Gemini 2.5 Flash Audio (set/25)",
                "Versão anterior do áudio nativo, estável e testada",
                List.of("speed"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.0-flash-001",
                "Gemini 2.0 Flash (stable)",
                "Versão fixada e estável do Flash 2.0 para produção",
                List.of("speed"),
                List.of("STT", "LLM"));
        m(
                "gemini-2.0-flash-lite-001",
                "Gemini 2.0 Flash Lite (stable)",
                "Versão fixada do Flash Lite — econômico e previsível",
                List.of("speed", "cost"),
                List.of("STT", "LLM"));
        // Anthropic
        m(
                "claude-opus-4-5",
                "Claude Opus 4.5",
                "Raciocínio avançado, contexto de 200K tokens, tarefas complexas",
                List.of("deep"),
                List.of("LLM"));
        m(
                "claude-sonnet-4-5",
                "Claude Sonnet 4.5",
                "Equilíbrio entre velocidade e profundidade analítica",
                List.of("deep", "speed"),
                List.of("LLM"));
        m(
                "claude-haiku-3-5-20241022",
                "Claude Haiku 3.5",
                "Resposta rápida, custo reduzido, bom para alto volume",
                List.of("speed", "cost"),
                List.of("LLM"));
        m(
                "claude-3-opus-20240229",
                "Claude 3 Opus",
                "Análise profunda, raciocínio lógico avançado",
                List.of("deep"),
                List.of("LLM"));
        // OpenAI STT
        m(
                "whisper-1",
                "Whisper 1",
                "Alta acurácia, robusto a sotaques e ruído de fundo",
                List.of("deep"),
                List.of("STT"));
        m(
                "gpt-4o-transcribe",
                "GPT-4o Transcribe",
                "Transcrição em tempo real com compreensão de contexto",
                List.of("speed", "deep"),
                List.of("STT"));
        // OpenAI LLM
        m(
                "gpt-4o",
                "GPT-4o",
                "Raciocínio avançado, multimodal, contexto de 128K tokens",
                List.of("deep"),
                List.of("LLM"));
        m(
                "gpt-4o-mini",
                "GPT-4o Mini",
                "Rápido, econômico, ideal para produção em alto volume",
                List.of("speed", "cost"),
                List.of("LLM"));
        m(
                "gpt-4-turbo",
                "GPT-4 Turbo",
                "Contexto longo, geração precisa e consistente",
                List.of("deep"),
                List.of("LLM"));
        m(
                "o1",
                "OpenAI o1",
                "Pensamento profundo passo a passo, resolução lógica complexa",
                List.of("deep"),
                List.of("LLM"));
        m(
                "o1-mini",
                "OpenAI o1 Mini",
                "Raciocínio estruturado com custo reduzido",
                List.of("deep", "cost"),
                List.of("LLM"));
        // OpenAI TTS
        m(
                "tts-1",
                "TTS-1",
                "Síntese rápida, adequada para alto volume de chamadas",
                List.of("speed"),
                List.of("TTS"));
        m(
                "tts-1-hd",
                "TTS-1 HD",
                "Máxima qualidade de voz, streaming suave",
                List.of("voice"),
                List.of("TTS"));
        m(
                "gpt-4o-mini-tts",
                "GPT-4o Mini TTS",
                "Voz expressiva com baixa latência, boa naturalidade",
                List.of("speed", "voice"),
                List.of("TTS"));
        // Grok
        m(
                "grok-3",
                "Grok 3",
                "Raciocínio profundo, contexto amplo e criatividade",
                List.of("deep"),
                List.of("LLM"));
        m(
                "grok-3-mini",
                "Grok 3 Mini",
                "Resposta rápida para tarefas objetivas",
                List.of("speed"),
                List.of("LLM"));
        m(
                "grok-2",
                "Grok 2",
                "Estável, bom custo-benefício para uso geral",
                List.of("cost"),
                List.of("LLM"));
        // Perplexity
        m(
                "sonar-pro",
                "Sonar Pro",
                "Raciocínio com pesquisa web em tempo real, fontes citadas",
                List.of("deep"),
                List.of("LLM"));
        m(
                "sonar",
                "Sonar",
                "Respostas rápidas com acesso à internet atualizada",
                List.of("speed"),
                List.of("LLM"));
        m(
                "sonar-reasoning",
                "Sonar Reasoning",
                "Lógica estruturada combinada com fontes recentes da web",
                List.of("deep"),
                List.of("LLM"));
        // ElevenLabs
        m(
                "eleven_turbo_v2_5",
                "Turbo v2.5",
                "Streaming ultra-rápido, voz natural, latência < 400ms",
                List.of("speed", "voice"),
                List.of("TTS"));
        m(
                "eleven_turbo_v2",
                "Turbo v2",
                "Rápido com boa expressividade vocal",
                List.of("speed", "voice"),
                List.of("TTS"));
        m(
                "eleven_multilingual_v2",
                "Multilingual v2",
                "Máxima naturalidade, excelente suporte PT-BR",
                List.of("voice"),
                List.of("TTS"));
        m(
                "eleven_flash_v2_5",
                "Flash v2.5",
                "Latência mínima para tempo real, boa para URA",
                List.of("speed"),
                List.of("TTS"));
        // Local
        m(
                "whisper-large-v3",
                "Whisper Large v3",
                "Offline, alta acurácia — dados não saem do servidor",
                List.of("priv", "deep"),
                List.of("STT"));
        m(
                "whisper-medium",
                "Whisper Medium",
                "Offline, boa velocidade, sem custo de API",
                List.of("priv", "speed"),
                List.of("STT"));
        m(
                "llama3.2",
                "Llama 3.2",
                "Leve, rápido, sem custo de API, privado",
                List.of("priv", "speed"),
                List.of("LLM"));
        m(
                "mistral",
                "Mistral",
                "Eficiente, bom raciocínio, completamente local",
                List.of("priv"),
                List.of("LLM"));
        m(
                "phi3",
                "Phi-3",
                "Compacto, respostas diretas, baixo consumo de RAM",
                List.of("priv", "cost"),
                List.of("LLM"));
        m(
                "gemma2",
                "Gemma 2",
                "Equilíbrio entre qualidade e uso de recursos",
                List.of("priv"),
                List.of("LLM"));
    }

    private static void m(
            String id, String display, String desc, List<String> tags, List<String> caps) {
        MODEL_METADATA.put(id, new AiModelInfo(id, display, desc, tags, caps));
    }

    /** Metadados catalogados para o model ID, se conhecido. */
    public static Optional<AiModelInfo> metadataFor(String id) {
        return Optional.ofNullable(MODEL_METADATA.get(id));
    }
}
