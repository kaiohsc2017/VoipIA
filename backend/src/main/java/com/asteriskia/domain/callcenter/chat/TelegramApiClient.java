package com.asteriskia.domain.callcenter.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * TelegramApiClient — wrapper fino sobre a API do Telegram Bot ({@code getUpdates}/
 * {@code sendMessage}), usada só por long polling (Fase 7e, D1/D2 — nunca webhook). O token do bot
 * é exigido pela própria API do Telegram no path da URL (não há alternativa de header oficial), o
 * que significa que qualquer {@code WebClientResponseException}/mensagem de erro que inclua a URI
 * completa vazaria o token — por isso todo tratamento de erro aqui é feito com
 * {@code e.getClass().getSimpleName()}, NUNCA {@code e.getMessage()} nem a URI da requisição
 * (mesma disciplina já usada para a API key do Gemini em
 * {@code CallCenterNpsTranscriptionScheduler}/{@code llm.py}). O token também nunca é logado
 * diretamente por este cliente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramApiClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String BASE_URL = "https://api.telegram.org";

    private final WebClient.Builder webClientBuilder;

    /** Um update recebido do Telegram — só os campos que este canal usa (mensagem de texto). */
    public record TelegramUpdate(long updateId, String chatId, String fromName, String text) {}

    /**
     * {@code getUpdates} com offset incremental — nunca solicita webhook, nunca abre porta nova.
     * {@code timeoutSeconds=0} faz long polling curto (retorna na hora se não houver update), que
     * é o que usamos aqui: a periodicidade já vem do próprio {@code @Scheduled}.
     */
    public List<TelegramUpdate> getUpdates(String botToken, long offset, int timeoutSeconds) {
        try {
            var webClient = webClientBuilder.baseUrl(BASE_URL).build();
            JsonNode response = webClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/bot{token}/getUpdates")
                            .queryParam("offset", offset)
                            .queryParam("timeout", timeoutSeconds)
                            .build(botToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(REQUEST_TIMEOUT);
            return parseUpdates(response);
        } catch (Exception e) {
            // Nunca e.getMessage() nem a URI da requisição — os dois incluiriam o token do bot,
            // que a própria API do Telegram exige no path (sem alternativa de header oficial).
            log.warn("Falha ao consultar getUpdates do Telegram (causa={}).", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** {@code sendMessage} — resposta do agente/bot/sistema de volta pro cliente no Telegram. */
    public void sendMessage(String botToken, String chatId, String text) {
        try {
            var webClient = webClientBuilder.baseUrl(BASE_URL).build();
            webClient
                    .post()
                    .uri(uriBuilder -> uriBuilder.path("/bot{token}/sendMessage").build(botToken))
                    .bodyValue(new SendMessageBody(chatId, text))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(REQUEST_TIMEOUT);
        } catch (Exception e) {
            log.warn("Falha ao enviar mensagem ao Telegram (chatId={}, causa={}).", chatId, e.getClass().getSimpleName());
        }
    }

    private record SendMessageBody(String chat_id, String text) {}

    /** {@code sendDocument} — anexo binário (relatório exportado, Fase 9c.6). Multipart, mesma
     * disciplina de nunca logar {@code e.getMessage()}/URI (o token vai no path). */
    public boolean sendDocument(String botToken, String chatId, byte[] content, String filename) {
        try {
            var webClient = webClientBuilder.baseUrl(BASE_URL).build();
            var builder = new org.springframework.http.client.MultipartBodyBuilder();
            builder.part("chat_id", chatId);
            builder.part("document", new org.springframework.core.io.ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            JsonNode response = webClient
                    .post()
                    .uri(uriBuilder -> uriBuilder.path("/bot{token}/sendDocument").build(botToken))
                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(builder.build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(REQUEST_TIMEOUT);
            return response != null && response.path("ok").asBoolean(false);
        } catch (Exception e) {
            log.warn("Falha ao enviar documento ao Telegram (chatId={}, causa={}).", chatId, e.getClass().getSimpleName());
            return false;
        }
    }

    private List<TelegramUpdate> parseUpdates(JsonNode response) {
        List<TelegramUpdate> updates = new ArrayList<>();
        if (response == null || !response.path("ok").asBoolean(false)) {
            return updates;
        }
        for (JsonNode result : response.path("result")) {
            long updateId = result.path("update_id").asLong();
            JsonNode message = result.path("message");
            if (message.isMissingNode()) {
                continue;
            }
            String text = message.path("text").asText(null);
            if (text == null) {
                // Mensagem sem texto (foto, sticker, etc.) — fora de escopo desta fatia (texto
                // puro, sem anexo/mídia via Telegram). O update ainda avança o offset (evita
                // reprocessamento em loop), só não vira mensagem de chat.
                updates.add(new TelegramUpdate(updateId, null, null, null));
                continue;
            }
            String chatId = message.path("chat").path("id").asText(null);
            String fromName = message.path("from").path("first_name").asText(null);
            updates.add(new TelegramUpdate(updateId, chatId, fromName, text));
        }
        return updates;
    }
}
