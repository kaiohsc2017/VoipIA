package com.asteriskia.domain.callcenter.flow.engine.ari;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * AriEventListener — WebSocket de eventos ARI (Fase 5b), event-driven, mesmo padrão de
 * reconexão automática do {@link com.asteriskia.domain.callcenter.interaction.CallCenterAmiEventListener}
 * (Fase 4) — mas sobre WebSocket, não socket raw AMI. {@code StasisStart} inicia uma execução do
 * {@link FlowExecutionEngine} em thread dedicada (o motor bloqueia em {@code promptChoice} — não
 * pode rodar na thread do WebSocket, senão trava a fila de eventos de todas as chamadas).
 *
 * <p><b>Não validado contra tráfego real</b> — mesma ressalva já registrada na Fase 4: sem uma
 * chamada de teste atravessando a extensão 6XXX, não há como confirmar 100% os nomes de campo do
 * payload ARI desta versão do Asterisk. O primeiro {@code StasisStart} real recebido em produção é
 * logado por completo (nível INFO, uma única vez) para permitir ajuste sem adivinhação.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AriEventListener extends TextWebSocketHandler {

    @Value("${app.asterisk.ari.base-url}")
    private String baseUrl;

    @Value("${app.asterisk.ari.user}")
    private String user;

    @Value("${app.asterisk.ari.password}")
    private String password;

    @Value("${app.asterisk.ari.app}")
    private String appName;

    @Value("${app.callcenter.ari.enabled:true}")
    private boolean enabled;

    private static final long RECONNECT_DELAY_MS = 5000;

    private final AriClient ariClient;
    private final AriPlaybackTracker playbackTracker;
    private final FlowExecutionEngine engine;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, AriVoiceChannelDriver> driversByChannelId = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private volatile boolean firstStasisStartLogged = false;

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("Listener de eventos ARI do Call Center desabilitado (app.callcenter.ari.enabled=false).");
            return;
        }
        running = true;
        var thread = new Thread(this::runLoop, "callcenter-ari-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
    }

    private void runLoop() {
        var client = new StandardWebSocketClient();
        while (running) {
            try {
                var session = client.execute(this, buildAuthHeaders(), java.net.URI.create(buildEventsUri())).get();
                // Bloqueia esta thread enquanto a sessão estiver aberta — o handler processa os
                // eventos via afterConnectionEstablished/handleTextMessage/afterConnectionClosed.
                while (running && session.isOpen()) {
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                if (running) {
                    log.warn(
                            "Listener ARI do Call Center: conexão perdida ({}), reconectando em {}ms.",
                            sanitize(e.getMessage()),
                            RECONNECT_DELAY_MS);
                }
            }
            if (running) {
                sleepQuietly(RECONNECT_DELAY_MS);
            }
        }
    }

    /** Autenticação via header Basic — nunca na query string, para não vazar a credencial em
     * logs de exceção de handshake (mesma classe de achado já corrigida para a API key do
     * Gemini neste projeto). */
    private WebSocketHttpHeaders buildAuthHeaders() {
        var headers = new WebSocketHttpHeaders();
        var credentials = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        headers.add("Authorization", "Basic " + credentials);
        return headers;
    }

    private String buildEventsUri() {
        var wsBase = baseUrl.replaceFirst("^http", "ws").replaceFirst("/ari$", "");
        return wsBase + "/ari/events?app=" + appName + "&subscribeAll=false";
    }

    /** Remove qualquer resquício de credencial de uma mensagem de exceção antes de logar —
     * defesa em profundidade mesmo com a autenticação já movida para o header. */
    private String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("(?i)(api_key|authorization)=[^&\\s]+", "$1=***");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Listener ARI do Call Center conectado (app={}).", appName);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("Sessão WebSocket ARI encerrada: {}", status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            var event = objectMapper.readTree(message.getPayload());
            handleEvent(event);
        } catch (Exception e) {
            log.warn("Listener ARI do Call Center: falha ao processar evento: {}", e.getMessage());
        }
    }

    private void handleEvent(JsonNode event) {
        var type = event.path("type").asText(null);
        if (type == null) {
            return;
        }
        switch (type) {
            case "StasisStart" -> onStasisStart(event);
            case "ChannelDtmfReceived" -> onDtmf(event);
            case "StasisEnd" -> onStasisEnd(event);
            case "PlaybackFinished" -> onPlaybackFinished(event);
            default -> {
                // Demais eventos ARI (ChannelStateChange, etc.) — fora do escopo desta sub-fase.
            }
        }
    }

    private void onStasisStart(JsonNode event) {
        if (!firstStasisStartLogged) {
            firstStasisStartLogged = true;
            log.info("Primeiro StasisStart real recebido — payload para conferência (sem ANI/caller): {}", redactCaller(event));
        }
        var channel = event.path("channel");
        var channelId = channel.path("id").asText(null);
        var extension = firstArg(event.path("args"));
        if (channelId == null || extension == null) {
            log.error("StasisStart sem channelId/extension utilizável — payload: {}", event);
            return;
        }
        var context = channel.path("dialplan").path("context").asText(null);
        if (context == null || context.isBlank()) {
            context = ariClient.getChannelContext(channelId);
        }
        var driver = new AriVoiceChannelDriver(ariClient, playbackTracker, channelId, context);
        driversByChannelId.put(channelId, driver);

        // channelUniqueId: assume-se igual ao channelId do ARI (formato usual do Asterisk 21) —
        // não confirmado contra tráfego real nesta VPS, mesma ressalva da Fase 4.
        var thread = new Thread(() -> engine.start(channelId, extension, channelId, driver), "callcenter-flow-" + channelId);
        thread.setDaemon(true);
        thread.start();
    }

    private void onDtmf(JsonNode event) {
        var channelId = event.path("channel").path("id").asText(null);
        var digit = event.path("digit").asText(null);
        if (channelId == null || digit == null) {
            return;
        }
        var driver = driversByChannelId.get(channelId);
        if (driver != null) {
            driver.onDtmfReceived(digit);
        }
    }

    private void onStasisEnd(JsonNode event) {
        var channelId = event.path("channel").path("id").asText(null);
        if (channelId == null) {
            return;
        }
        var driver = driversByChannelId.remove(channelId);
        if (driver != null) {
            driver.onChannelEnded();
        }
        engine.onChannelEnded(channelId);
    }

    private void onPlaybackFinished(JsonNode event) {
        var playbackId = event.path("playback").path("id").asText(null);
        if (playbackId != null) {
            playbackTracker.complete(playbackId);
        }
    }

    /** Remove o campo `channel.caller` (número/ANI, tratado como PII no restante do projeto —
     * ver regras de exibição de ANI do Insights) antes de logar o payload de diagnóstico. */
    private JsonNode redactCaller(JsonNode event) {
        var copy = event.deepCopy();
        var channel = copy.path("channel");
        if (channel.isObject() && channel.has("caller")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) channel).put("caller", "***redigido***");
        }
        return copy;
    }

    private String firstArg(JsonNode argsNode) {
        if (argsNode == null || !argsNode.isArray() || argsNode.isEmpty()) {
            return null;
        }
        return argsNode.get(0).asText(null);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
