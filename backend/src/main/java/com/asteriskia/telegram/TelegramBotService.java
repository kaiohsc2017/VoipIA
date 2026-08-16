package com.asteriskia.telegram;

import com.asteriskia.domain.config.ConfigService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * TelegramBotService — Envio de mensagens via Telegram Bot API.
 *
 * <p>Configurações lidas dinamicamente via ConfigService (banco de dados). Alterações na tela de
 * Settings refletem sem restart de container (TTL 60s).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService {

    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot";
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final ConfigService config;
    private final WebClient.Builder webClientBuilder;

    public String sendZabbixAlert(
            String severity,
            String host,
            String incidentSummary,
            String phoneNumber,
            String callStatus) {
        String callStatusEmoji = "ATENDIDA".equalsIgnoreCase(callStatus) ? "✅" : "❌";
        String severityEmoji = getSeverityEmoji(severity);

        String message =
                String.format(
                        """
                🚨 *ALERTA CRÍTICO DE INFRAESTRUTURA*

                %s *Severidade:* %s
                🖥️ *Host:* `%s`

                📋 *Incidente:*
                %s

                ━━━━━━━━━━━━━━━━
                📞 *Ligação realizada para:* `%s`
                %s *Status da chamada:* %s

                🕐 *Data/Hora:* %s

                _Mensagem automática — VoipIA_
                """,
                        severityEmoji,
                        severity,
                        host,
                        incidentSummary,
                        phoneNumber,
                        callStatusEmoji,
                        callStatus,
                        LocalDateTime.now().format(FORMATTER));

        sendMessage(message);
        return message;
    }

    public void sendMessage(String text) {
        // Lê token e chatId em runtime — sem restart
        String botToken = config.get("TELEGRAM_BOT_TOKEN");
        String chatId = config.get("TELEGRAM_CHAT_ID");

        if (botToken.isBlank() || chatId.isBlank()) {
            log.warn(
                    "Telegram não configurado — mensagem não enviada (verifique Settings → Telegram)");
            return;
        }

        String url = TELEGRAM_API_URL + botToken + "/sendMessage";
        webClientBuilder
                .build()
                .post()
                .uri(url)
                .bodyValue(Map.of("chat_id", chatId, "text", text, "parse_mode", "Markdown"))
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(r -> log.info("Telegram: mensagem enviada com sucesso"))
                .doOnError(e -> log.error("Telegram: erro ao enviar — {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private String getSeverityEmoji(String severity) {
        return switch (severity.toLowerCase()) {
            case "disaster" -> "💀";
            case "high" -> "🔴";
            case "average" -> "🟠";
            case "warning" -> "🟡";
            default -> "⚪";
        };
    }
}
