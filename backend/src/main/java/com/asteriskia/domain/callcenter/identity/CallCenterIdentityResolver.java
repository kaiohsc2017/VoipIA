package com.asteriskia.domain.callcenter.identity;

import com.asteriskia.domain.ai.AiProviderService;
import com.asteriskia.domain.callcenter.reports.AniNormalizer;
import com.asteriskia.integration.ad.AdUser;
import com.asteriskia.integration.ad.AdUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * CallCenterIdentityResolver — cascata de identificação de contato (Fase 14 do plano Call
 * Center — decisões D7/D8): login de rede autenticado &gt; entrada falada/digitada confirmada
 * &gt; ANI. Nunca lança exceção por não identificar — {@code UNRESOLVED} é um resultado normal,
 * consumido pelo chamador (nó de fluxo, início de sessão de chat) para decidir se segue sem
 * screen pop.
 *
 * <p>A transcrição de voz chama a API do Gemini diretamente, mesmo padrão já usado por
 * {@code CallCenterNpsTranscriptionScheduler} (Fase 21) — chave via header
 * {@code x-goog-api-key}, nunca query string; nenhuma exceção de rede tem
 * {@code e.getMessage()} logado (evita vazar URI/chave em log de erro). Diferente daquele
 * scheduler, aqui a chamada é SÍNCRONA e dentro da ligação em curso — decisão deliberada desta
 * fase: a confirmação falada só faz sentido em tempo real, não pode esperar um job de fundo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterIdentityResolver {

    /** Abaixo deste score de similaridade trigram, o candidato nunca é oferecido para
     * confirmação — evita "confirmar" um nome completamente diferente do que foi falado. */
    private static final double FUZZY_MATCH_THRESHOLD = 0.3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    @Value("${app.callcenter.identity.stt-model:gemini-2.5-flash}")
    private String model;

    private final AdUserRepository adUserRepository;
    private final AiProviderService aiProviderService;
    private final CcIdentityResolutionLogRepository resolutionLogRepository;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    /** Login de rede já autenticado (chat interno via JWT) ou digitado/falado — busca exata,
     * sem tolerância a erro de transcrição (decisão: login é curto e o usuário pode repetir se
     * errar; tolerância a erro fica só na busca por nome falado). */
    public Optional<ResolvedIdentity> resolveByLogin(String login, IdentitySource source) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        return adUserRepository
                .findBySamAccountNameIgnoreCase(login.trim())
                .map(adUser -> ResolvedIdentity.exact(adUser, source));
    }

    /** Fallback por ANI (D7): compara contra {@code ad_users.telephone_number}, normalizado com o
     * mesmo {@link AniNormalizer} já usado na Fase 27 (remove código do país "55", insere o 9º
     * dígito do celular quando ausente) — sem isso, um ANI com "+55" ou sem o 9º dígito nunca
     * bateria contra o telefone cadastrado no AD. A cascata "extensão do Call Center →
     * app_users.extension" descrita
     * no plano fica como gap aceito nesta fatia (documentado no relatório final) — exigiria
     * decidir como uma extensão interna de app_users se relaciona 1:1 com um AdUser, o que não
     * existe hoje no modelo (mesma classe de decisão de produto ainda em aberto para BU em
     * Alertas Zabbix). */
    public Optional<ResolvedIdentity> resolveByAni(String ani) {
        if (ani == null || ani.isBlank()) {
            return Optional.empty();
        }
        String digitsOnly = AniNormalizer.normalize(ani);
        if (digitsOnly == null || digitsOnly.isBlank()) {
            return Optional.empty();
        }
        return adUserRepository
                .findByTelephoneNumber(digitsOnly)
                .map(adUser -> ResolvedIdentity.exact(adUser, IdentitySource.ANI));
    }

    /** Transcreve um áudio curto (login/nome falado, ou confirmação "sim"/"não") via Gemini.
     * Deliberadamente sem {@code @Transactional} — I/O de rede bloqueante, nenhum trabalho de
     * banco no meio (mesmo racional documentado em {@code CallCenterNpsTranscriptionScheduler}). */
    public TranscriptionResult transcribe(byte[] audioWav) {
        String apiKey = aiProviderService.getRawKey("gemini");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Sem API key do Gemini configurada — identificação de contato por voz indisponível.");
            return new TranscriptionResult("", BigDecimal.ZERO);
        }
        try {
            var webClient = webClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
            var body = objectMapper.createObjectNode();
            var contents = body.putArray("contents").addObject();
            var parts = contents.putArray("parts");
            parts.addObject()
                    .put(
                            "text",
                            "Transcreva literalmente a fala a seguir (curta — um nome de login ou uma "
                                    + "resposta de sim/não). Responda só com o texto transcrito, sem comentário.");
            var inlineData = parts.addObject().putObject("inline_data");
            inlineData.put("mime_type", "audio/wav");
            inlineData.put("data", Base64.getEncoder().encodeToString(audioWav));

            JsonNode response =
                    webClient
                            .post()
                            .uri("/v1beta/models/{model}:generateContent", model)
                            .header("x-goog-api-key", apiKey)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block(REQUEST_TIMEOUT);

            String text = extractText(response);
            BigDecimal cost = extractCost(response);
            return new TranscriptionResult(text == null ? "" : text.trim(), cost);
        } catch (Exception e) {
            // Nunca e.getMessage() — WebClientResponseException inclui a URI completa da
            // requisição, e a chave viaja em header (não query string), mas não custa nada não
            // depender disso no log (defesa em profundidade, mesma disciplina do NPS).
            log.warn("Falha ao transcrever áudio de identificação (causa={})", e.getClass().getSimpleName());
            return new TranscriptionResult("", BigDecimal.ZERO);
        }
    }

    /** Busca aproximada por nome falado (já transcrito) contra {@code display_name} — só retorna
     * candidato acima do limiar de similaridade; o chamador ainda precisa confirmar falado antes
     * de usar (D7 — "confirmação falada obrigatória"). */
    public Optional<ResolvedIdentity> findFuzzyCandidateByName(String spokenName) {
        if (spokenName == null || spokenName.isBlank()) {
            return Optional.empty();
        }
        return adUserRepository
                .findBestFuzzyMatchByDisplayName(spokenName.trim())
                .filter(match -> match.getScore() != null && match.getScore() >= FUZZY_MATCH_THRESHOLD)
                .flatMap(match -> adUserRepository.findById(match.getId()))
                .map(adUser -> ResolvedIdentity.fuzzy(adUser, 0.0));
    }

    /** Um "sim" falado é aceito de forma tolerante a ruído de transcrição — variações comuns
     * ("sim", "isso", "correto", "sim é") contam como confirmação positiva; qualquer outra coisa
     * (incluindo silêncio/erro de transcrição) é tratada como negativa — fail-closed: nunca
     * assume identidade sem confirmação clara. */
    public boolean isSpokenConfirmationPositive(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return false;
        }
        String normalized = transcript.trim().toLowerCase();
        return normalized.contains("sim") || normalized.contains("isso mesmo") || normalized.contains("correto");
    }

    @Transactional
    public void logResolution(String channel, String outcome, BigDecimal costUsd) {
        resolutionLogRepository.save(
                CcIdentityResolutionLog.builder()
                        .channel(channel)
                        .outcome(outcome)
                        .aiCostUsd(costUsd == null ? BigDecimal.ZERO : costUsd)
                        .build());
    }

    private String extractText(JsonNode response) {
        if (response == null) {
            return null;
        }
        var candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        var parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return null;
        }
        return parts.get(0).path("text").asText(null);
    }

    /** Custo aproximado — sem o pricing por modelo já resolvido aqui (diferente do scheduler de
     * NPS, que consulta {@code AiModelPricingRepository}); nesta fatia o custo é estimado por
     * tokens reportados pelo próprio Gemini a um preço fixo conservador, documentado como
     * simplificação aceita (não é a fonte de verdade de custo do módulo Financeiro para as
     * demais frentes, só um indicativo para o alerta desta frente específica). */
    private BigDecimal extractCost(JsonNode response) {
        if (response == null) {
            return BigDecimal.ZERO;
        }
        var usage = response.path("usageMetadata");
        long totalTokens = usage.path("totalTokenCount").asLong(0);
        // Preço fixo conservador (Gemini 2.5 Flash, ordem de grandeza de $0.30/milhão de tokens)
        // — aceito como estimativa nesta fatia; ver nota da classe acima.
        return BigDecimal.valueOf(totalTokens)
                .multiply(BigDecimal.valueOf(0.30))
                .divide(ONE_MILLION, 6, java.math.RoundingMode.HALF_UP);
    }

    public record TranscriptionResult(String transcript, BigDecimal costUsd) {}
}
