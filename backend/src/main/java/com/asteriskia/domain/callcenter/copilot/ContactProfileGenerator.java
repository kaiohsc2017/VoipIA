package com.asteriskia.domain.callcenter.copilot;

import com.asteriskia.domain.ai.AiModelPricing;
import com.asteriskia.domain.ai.AiModelPricingRepository;
import com.asteriskia.domain.ai.AiProviderService;
import com.asteriskia.integration.ad.AdUser;
import com.asteriskia.integration.ad.AdUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ContactProfileGenerator — geração assíncrona do perfil de IA (Fase 16.2). Classe separada de
 * {@link ContactProfileService} pelo mesmo motivo do {@code AuditWriter}/{@code
 * ChatTranscriptExportService}: um método {@code @Async} chamado por auto-invocação não passaria
 * pelo proxy do Spring e rodaria síncrono, sem querer — aqui isso seria especialmente grave, pois
 * o objetivo explícito da Fase 16.2 é NUNCA bloquear o atendimento esperando o LLM.
 *
 * <p>Entrada do modelo: histórico resumido (Fase 16.1) + dados do AD já espelhados localmente
 * (cargo/área/gestor) — nunca o áudio bruto, só o texto que os serviços de histórico já expõem
 * (as transcrições em si, quando existirem no histórico, já chegam mascaradas desde a ingestão —
 * {@code insights/src/masking.py} — então esta classe nunca precisa mascarar de novo). Chamada
 * direta ao Gemini (não via serviço Python) — mesmo padrão já estabelecido no domínio Call Center
 * para geração de texto (Fase 8/14/21/25): chave só em header {@code x-goog-api-key}, nunca query
 * string, e nenhuma exceção de rede loga {@code e.getMessage()} (evita vazar URI/chave em log).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactProfileGenerator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Value("${app.callcenter.copiloto.model:gemini-2.5-flash}")
    private String model;

    private final CcContactProfileRepository profileRepository;
    private final AdUserRepository adUserRepository;
    private final AiProviderService aiProviderService;
    private final AiModelPricingRepository pricingRepository;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    /** Retorna {@code CompletableFuture<Void>} (não {@code void}) só para o chamador saber QUANDO
     * a geração termina — nunca para esperar por ela no caminho de atendimento; {@link
     * ContactProfileService} usa isso apenas para liberar o contato de {@code inFlight} assim que
     * a chamada ao Gemini de fato conclui (sucesso ou falha), não assim que a chamada assíncrona é
     * apenas disparada. */
    @Async
    public java.util.concurrent.CompletableFuture<Void> generate(
            String resolvedAdSam, Long interactionId, List<ContactHistoryItem> history) {
        try {
            var apiKey = aiProviderService.getRawKey("gemini");
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("Sem API key do Gemini configurada — copiloto de IA indisponível para gerar perfil.");
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            var adUser = adUserRepository.findBySamAccountNameIgnoreCase(resolvedAdSam).orElse(null);
            var generated = callGemini(adUser, history, apiKey);
            var content = clamp(generated.content());
            profileRepository.save(
                    CcContactProfile.builder()
                            .resolvedAdSam(resolvedAdSam)
                            .interactionId(interactionId)
                            .profileJson(objectMapper.writeValueAsString(content))
                            .model(model)
                            .inputTokens(generated.inputTokens())
                            .outputTokens(generated.outputTokens())
                            .costUsd(generated.costUsd())
                            .build());
        } catch (Exception e) {
            // Nunca e.getMessage() — mesma disciplina do resto do domínio Call Center (Fase
            // 14/21/25): WebClientResponseException poderia incluir dado sensível da requisição.
            log.warn(
                    "Falha ao gerar perfil de IA do contato (sam presente={}, causa={})",
                    resolvedAdSam != null,
                    e.getClass().getSimpleName());
        }
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /** Nunca persiste o valor cru do modelo — {@code riscoEscalonamento} fora de [0,1] é
     * clampado, mesma lição do overflow numérico de {@code call_insights.aderencia_script}
     * (Fase 8). Listas nulas viram vazias para nunca quebrar a UI com NPE. */
    // package-private (não private) só para ser testável diretamente, sem simular toda a
    // cadeia reativa do WebClient — mesma exceção de visibilidade já aceita em outros pontos do
    // domínio Call Center para métodos de clamp/validação isolados.
    ContactProfileContent clamp(ContactProfileContent raw) {
        var risco = raw.riscoEscalonamento();
        if (risco == null) {
            risco = BigDecimal.ZERO;
        } else if (risco.compareTo(BigDecimal.ZERO) < 0) {
            risco = BigDecimal.ZERO;
        } else if (risco.compareTo(BigDecimal.ONE) > 0) {
            risco = BigDecimal.ONE;
        }
        return new ContactProfileContent(
                raw.resumoPerfil() == null ? "" : raw.resumoPerfil(),
                raw.sentimentoHistorico() == null ? "" : raw.sentimentoHistorico(),
                raw.temasRecorrentes() == null ? List.of() : raw.temasRecorrentes(),
                risco,
                raw.acoesSugeridas() == null ? List.of() : raw.acoesSugeridas());
    }

    private record GeneratedProfile(
            ContactProfileContent content, int inputTokens, int outputTokens, BigDecimal costUsd) {}

    private GeneratedProfile callGemini(AdUser adUser, List<ContactHistoryItem> history, String apiKey) {
        var webClient = webClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();

        var body = objectMapper.createObjectNode();
        var parts = body.putArray("contents").addObject().putArray("parts");
        parts.addObject().put("text", buildPrompt(adUser, history));

        var generationConfig = body.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        var schema = generationConfig.putObject("responseSchema");
        schema.put("type", "OBJECT");
        var properties = schema.putObject("properties");
        properties.putObject("resumoPerfil").put("type", "STRING");
        properties.putObject("sentimentoHistorico").put("type", "STRING");
        var temas = properties.putObject("temasRecorrentes");
        temas.put("type", "ARRAY");
        temas.putObject("items").put("type", "STRING");
        properties.putObject("riscoEscalonamento").put("type", "NUMBER");
        var acoes = properties.putObject("acoesSugeridas");
        acoes.put("type", "ARRAY");
        var acaoItem = acoes.putObject("items");
        acaoItem.put("type", "OBJECT");
        var acaoProps = acaoItem.putObject("properties");
        acaoProps.putObject("acao").put("type", "STRING");
        acaoProps.putObject("justificativa").put("type", "STRING");
        acaoItem.putArray("required").add("acao").add("justificativa");
        schema.putArray("required")
                .add("resumoPerfil")
                .add("sentimentoHistorico")
                .add("temasRecorrentes")
                .add("riscoEscalonamento")
                .add("acoesSugeridas");

        JsonNode response =
                webClient
                        .post()
                        .uri("/v1beta/models/{model}:generateContent", model)
                        .header("x-goog-api-key", apiKey)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(REQUEST_TIMEOUT);
        if (response == null) {
            throw new IllegalStateException("Resposta vazia do Gemini");
        }
        var text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("{}");
        ContactProfileContent content;
        try {
            content = objectMapper.readValue(text, ContactProfileContent.class);
        } catch (Exception parseError) {
            content = new ContactProfileContent("", "", List.of(), BigDecimal.ZERO, List.of());
        }
        var promptTokens = response.path("usageMetadata").path("promptTokenCount").asInt(0);
        var candidateTokens = response.path("usageMetadata").path("candidatesTokenCount").asInt(0);
        return new GeneratedProfile(content, promptTokens, candidateTokens, costFor(promptTokens, candidateTokens));
    }

    /** Só texto já disponível localmente — nunca consulta o AD ao vivo, nunca reabre áudio. */
    private String buildPrompt(AdUser adUser, List<ContactHistoryItem> history) {
        var sb = new StringBuilder();
        sb.append(
                "Você é um copiloto de atendimento de um call center corporativo interno. Gere um perfil "
                        + "objetivo do contato abaixo e sugira ações para o agente conduzir o atendimento atual. "
                        + "Nunca afirme fatos como certeza sobre a pessoa além do que os dados abaixo mostram — "
                        + "trate tudo como indício, não verdade absoluta. Responda em português.\n\n");
        if (adUser != null) {
            sb.append("Dados do contato (cadastro interno):\n")
                    .append("- Nome: ").append(nullToDash(adUser.getDisplayName())).append('\n')
                    .append("- Departamento: ").append(nullToDash(adUser.getDepartment())).append('\n')
                    .append("- Cargo: ").append(nullToDash(adUser.getTitle())).append('\n')
                    .append("- Gestor (login): ").append(nullToDash(adUser.getManagerSam())).append("\n\n");
        }
        if (history.isEmpty()) {
            sb.append("Sem atendimentos anteriores registrados para este contato.\n");
        } else {
            sb.append("Histórico de atendimentos anteriores (mais recente primeiro):\n");
            for (var item : history) {
                sb.append("- [")
                        .append(item.channel())
                        .append("] ")
                        .append(item.startedAt() == null ? "data desconhecida" : item.startedAt().format(DATE_FMT))
                        .append(" — fila/canal: ")
                        .append(nullToDash(item.queueName()))
                        .append(", agente: ")
                        .append(nullToDash(item.agentName()))
                        .append(", tabulação: ")
                        .append(nullToDash(item.dispositionLabel()))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private BigDecimal costFor(int tokensIn, int tokensOut) {
        AiModelPricing price = pricingRepository.findById(model).orElse(null);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        var input = BigDecimal.valueOf(tokensIn)
                .multiply(price.getPricePerMillionInputUsd())
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        var output = BigDecimal.valueOf(tokensOut)
                .multiply(price.getPricePerMillionOutputUsd())
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        return input.add(output);
    }
}
