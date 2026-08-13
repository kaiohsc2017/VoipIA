package com.asteriskia.domain.callcenter.flow.engine.ari;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * AriClient — cliente REST do ARI (Fase 5b), estilo síncrono (bloqueante via {@code .block()}),
 * consistente com o resto do projeto — não introduz reativo em cascata. Autenticação básica HTTP,
 * mesmo usuário/senha do {@code ari.conf} (variáveis {@code AST_ARI_USER}/{@code AST_ARI_PASSWORD},
 * já confirmadas funcionando em runtime nesta VPS).
 */
@Slf4j
@Component
public class AriClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;

    public AriClient(
            @Value("${app.asterisk.ari.base-url}") String baseUrl,
            @Value("${app.asterisk.ari.user}") String user,
            @Value("${app.asterisk.ari.password}") String password) {
        var credentials = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.webClient =
                WebClient.builder().baseUrl(baseUrl).defaultHeader("Authorization", "Basic " + credentials).build();
    }

    public void answer(String channelId) {
        webClient.post().uri("/channels/" + channelId + "/answer").retrieve().toBodilessEntity().block(REQUEST_TIMEOUT);
    }

    /** Inicia a reprodução e devolve o {@code playbackId} do ARI (correlacionado por {@link AriPlaybackTracker}). */
    public String play(String channelId, String media) {
        var uri = UriComponentsBuilder.fromPath("/channels/{id}/play").queryParam("media", media).build(channelId);
        var response = webClient.post().uri(uri).retrieve().bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
        return response == null ? null : response.path("id").asText(null);
    }

    /** Contexto de dialplan atual do canal (ex.: "ramais-internos") — Stasis() não muda o contexto. */
    public String getChannelContext(String channelId) {
        var response = webClient.get().uri("/channels/" + channelId).retrieve().bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
        return response == null ? null : response.path("dialplan").path("context").asText(null);
    }

    public void continueInDialplan(String channelId, String context, String extension, int priority) {
        var uri =
                UriComponentsBuilder.fromPath("/channels/{id}/continue")
                        .queryParam("context", context)
                        .queryParam("extension", extension)
                        .queryParam("priority", priority)
                        .build(channelId);
        webClient.post().uri(uri).retrieve().toBodilessEntity().block(REQUEST_TIMEOUT);
    }

    public void hangup(String channelId) {
        try {
            webClient.delete().uri("/channels/" + channelId).retrieve().toBodilessEntity().block(REQUEST_TIMEOUT);
        } catch (Exception e) {
            // Canal já pode ter caído (StasisEnd concorrente) — hangup best-effort, nunca propaga.
            log.debug("Hangup via ARI falhou para o canal {} (provavelmente já encerrado): {}", channelId, e.getMessage());
        }
    }

    public void setChannelVar(String channelId, String name, String value) {
        var uri =
                UriComponentsBuilder.fromPath("/channels/{id}/variable")
                        .queryParam("variable", name)
                        .queryParam("value", value == null ? "" : value)
                        .build(channelId);
        webClient.post().uri(uri).retrieve().toBodilessEntity().block(REQUEST_TIMEOUT);
    }

    public String getChannelVar(String channelId, String name) {
        var uri = UriComponentsBuilder.fromPath("/channels/{id}/variable").queryParam("variable", name).build(channelId);
        try {
            var response = webClient.get().uri(uri).retrieve().bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            return response == null ? null : response.path("value").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Grava o canal (Fase 21 — resposta falada/comentário de NPS) em
     * {@code &lt;astspooldir&gt;/recording/&lt;recordingName&gt;.wav}. {@code beep} avisa o cliente que a
     * gravação começou; {@code terminateOn} ("#" ou "none") permite ao cliente encerrar a
     * resposta antes do {@code maxDurationSeconds}. Não bloqueia até o fim — quem chama espera
     * {@code RecordingFinished} via {@link AriRecordingTracker}. */
    public void record(String channelId, String recordingName, int maxDurationSeconds, boolean beep, String terminateOn) {
        var uri =
                UriComponentsBuilder.fromPath("/channels/{id}/record")
                        .queryParam("name", recordingName)
                        .queryParam("format", "wav")
                        .queryParam("maxDurationSeconds", maxDurationSeconds)
                        .queryParam("beep", beep)
                        .queryParam("terminateOn", terminateOn)
                        .queryParam("ifExists", "overwrite")
                        .build(channelId);
        webClient.post().uri(uri).retrieve().toBodilessEntity().block(REQUEST_TIMEOUT);
    }

}
