package com.asteriskia.domain.callcenter.nps;

import com.asteriskia.domain.ai.AiModelPricing;
import com.asteriskia.domain.ai.AiModelPricingRepository;
import com.asteriskia.domain.ai.AiProviderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * CallCenterNpsTranscriptionScheduler — transcrição + classificação (0-{@code scaleMax}) das
 * respostas FALADA_IA da pesquisa de satisfação (Fase 21). Roda fora da chamada em curso —
 * nenhuma IA é chamada durante a ligação (mesmo princípio de D21: "nenhuma tela/passo em
 * atendimento dispara IA"); o cliente já desligou quando isto roda.
 *
 * <p>Chama a API do Gemini direto (mesma chave/padrão de {@link AiProviderService}, sem
 * dependência do serviço Python de Insights, que não tem servidor HTTP — só um polling loop
 * próprio). Falha de transcrição de uma resposta nunca impede as demais: cada resposta é
 * processada e persistida independentemente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallCenterNpsTranscriptionScheduler {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    @Value("${app.callcenter.nps.stt-model:gemini-2.5-flash}")
    private String model;

    private final CcSurveyResponseRepository responseRepository;
    private final AiProviderService aiProviderService;
    private final AiModelPricingRepository pricingRepository;
    private final CallCenterSurveyRunner surveyRunner;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    @Scheduled(cron = "${app.callcenter.nps.transcription-cron:0 */2 * * * ?}")
    public void processPending() {
        var pending = responseRepository.findByAudioPathIsNotNullAndTranscriptIsNullAndQuestion_Survey_Mode(
                SurveyMode.FALADA_IA);
        if (pending.isEmpty()) {
            return;
        }
        var apiKey = aiProviderService.getRawKey("gemini");
        if (apiKey.isBlank()) {
            log.warn("Sem API key do Gemini configurada — {} resposta(s) de NPS falada seguem pendentes.", pending.size());
            return;
        }
        for (var response : pending) {
            processOne(response, apiKey);
        }
    }

    /** Deliberadamente sem {@code @Transactional} aqui (achado de revisão): o corpo faz I/O de
     * disco e uma chamada HTTP bloqueante ao Gemini (até 30s) sem nenhum trabalho de banco
     * envolvido — abrir transação em volta disso prenderia uma conexão do pool por até 30s por
     * resposta, serialmente, para cada resposta pendente do lote. A persistência
     * ({@code responseRepository.save}) e o recálculo ({@link CallCenterSurveyRunner#recomputeInteractionNpsScore})
     * já são transacionais por conta própria, chamados só depois que a parte de I/O termina. */
    void processOne(CcSurveyResponse response, String apiKey) {
        try {
            byte[] audio = Files.readAllBytes(Path.of(response.getAudioPath()));
            var result = transcribeAndClassify(audio, response.getQuestion().getSurvey().getScaleMax(), apiKey);
            saveResult(response, result);
            log.info("NPS falada transcrita: responseId={} score={}", response.getId(), result.score());
        } catch (IOException e) {
            log.warn("Áudio de resposta FALADA_IA não encontrado/legível (responseId={}, path={}): {}",
                    response.getId(), response.getAudioPath(), e.getMessage());
        } catch (Exception e) {
            // Nunca e.getMessage() aqui — defesa em profundidade: WebClientResponseException
            // inclui a URI completa da requisição na mensagem; mesmo com a chave já movida para
            // header (não mais query string), não custa nada não depender disso no log.
            log.warn("Falha ao transcrever/classificar resposta FALADA_IA (responseId={}, causa={})",
                    response.getId(), e.getClass().getSimpleName());
        }
    }

    // Sem @Transactional aqui (autoinvocação de dentro de processOne não passaria pelo proxy do
    // Spring de qualquer forma — a anotação seria inerte e enganosa). responseRepository.save é
    // transacional por conta própria (bean do Spring Data), e recomputeInteractionNpsScore tem
    // sua própria transação curta (chamada a outro bean, essa sim passa pelo proxy).
    private void saveResult(CcSurveyResponse response, TranscriptionResult result) {
        response.setTranscript(result.transcript());
        response.setValue(result.score());
        response.setAiCostUsd(result.costUsd());
        responseRepository.save(response);
        surveyRunner.recomputeInteractionNpsScore(response.getInteraction(), response.getInteraction().getQueue());
    }

    private record TranscriptionResult(String transcript, Integer score, BigDecimal costUsd) {}

    private TranscriptionResult transcribeAndClassify(byte[] audio, int scaleMax, String apiKey) {
        var webClient = webClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
        var prompt =
                "Transcreva o áudio a seguir (resposta de um cliente a uma pesquisa de satisfação "
                        + "pós-atendimento) e classifique o quão satisfeito o cliente parece estar numa "
                        + "escala inteira de 0 a " + scaleMax + " (0 = muito insatisfeito, "
                        + scaleMax + " = muito satisfeito), com base no conteúdo e no tom da fala.";
        var body =
                objectMapper.createObjectNode();
        var contents = body.putArray("contents").addObject();
        var parts = contents.putArray("parts");
        parts.addObject().put("text", prompt);
        var inlineData = parts.addObject().putObject("inline_data");
        inlineData.put("mime_type", "audio/wav");
        inlineData.put("data", Base64.getEncoder().encodeToString(audio));

        var generationConfig = body.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        var schema = generationConfig.putObject("responseSchema");
        schema.put("type", "OBJECT");
        var properties = schema.putObject("properties");
        properties.putObject("transcript").put("type", "STRING");
        properties.putObject("score").put("type", "INTEGER");
        schema.putArray("required").add("transcript").add("score");

        // Chave via header (x-goog-api-key), nunca query string — achado CRITICAL de segurança
        // desta fase: WebClientResponseException.getMessage() inclui a URI completa da
        // requisição, então ?key=... vazaria a chave em qualquer log de erro (mesma classe de
        // achado já corrigida antes neste projeto para a API key do Gemini em llm.py).
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
        var parsed = parseJsonSafely(text);
        var promptTokens = response.path("usageMetadata").path("promptTokenCount").asInt(0);
        var candidateTokens = response.path("usageMetadata").path("candidatesTokenCount").asInt(0);
        var cost = costFor(model, promptTokens, candidateTokens);
        // score ausente/não-numérico (resposta fora do schema esperado) fica null — nunca
        // clampado pra 0, que é uma nota real (cliente muito insatisfeito), não "sem nota".
        var scoreNode = parsed.path("score");
        Integer score = scoreNode.isInt() ? Math.max(0, Math.min(scaleMax, scoreNode.asInt())) : null;
        return new TranscriptionResult(parsed.path("transcript").asText(""), score, cost);
    }

    private JsonNode parseJsonSafely(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (IOException e) {
            return objectMapper.createObjectNode();
        }
    }

    private BigDecimal costFor(String modelId, int tokensIn, int tokensOut) {
        AiModelPricing price = pricingRepository.findById(modelId).orElse(null);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        var input =
                BigDecimal.valueOf(tokensIn)
                        .multiply(price.getPricePerMillionInputUsd())
                        .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        var output =
                BigDecimal.valueOf(tokensOut)
                        .multiply(price.getPricePerMillionOutputUsd())
                        .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        return input.add(output);
    }
}
