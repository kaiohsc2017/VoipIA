package com.asteriskia.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * TelegramBotService — Serviço de envio de mensagens via Telegram Bot API.
 *
 * Responsabilidades:
 *   - Enviar alertas do Módulo 3 (incidentes Zabbix)
 *   - Notificar status de chamadas de alerta (atendida / não atendida)
 *   - Enviar mensagens genéricas de erro do sistema
 *
 * Configuração necessária no .env:
 *   TELEGRAM_BOT_TOKEN=123456:ABC...
 *   TELEGRAM_CHAT_ID=-1001234567890
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService {

    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @Value("${app.telegram.bot-token}")
    private String botToken;

    @Value("${app.telegram.chat-id}")
    private String chatId;

    private final WebClient.Builder webClientBuilder;

    /**
     * Envia alerta de incidente Zabbix com status da ligação telefônica.
     *
     * @param severity        Severidade do incidente (ex: "High", "Disaster")
     * @param host            Host afetado
     * @param incidentSummary Descrição do incidente
     * @param phoneNumber     Número que foi discado
     * @param callStatus      Status da chamada ("ATENDIDA" ou "NÃO ATENDIDA")
     * @return Conteúdo da mensagem enviada
     */
    public String sendZabbixAlert(
            String severity,
            String host,
            String incidentSummary,
            String phoneNumber,
            String callStatus
    ) {
        String callStatusEmoji = "ATENDIDA".equalsIgnoreCase(callStatus) ? "✅" : "❌";
        String severityEmoji = getSeverityEmoji(severity);

        String message = String.format(
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
                
                _Mensagem automática — AsteriskIA_
                """,
                severityEmoji, severity,
                host,
                incidentSummary,
                phoneNumber,
                callStatusEmoji, callStatus,
                LocalDateTime.now().format(FORMATTER)
        );

        sendMessage(message);
        return message;
    }

    /**
     * Envia mensagem de texto simples ao canal configurado.
     *
     * @param text Texto da mensagem (suporta Markdown)
     */
    public void sendMessage(String text) {
        String url = TELEGRAM_API_URL + botToken + "/sendMessage";

        webClientBuilder.build()
                .post()
                .uri(url)
                .bodyValue(Map.of(
                        "chat_id", chatId,
                        "text", text,
                        "parse_mode", "Markdown"
                ))
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(response -> log.info("Telegram: mensagem enviada com sucesso"))
                .doOnError(error -> log.error("Telegram: erro ao enviar mensagem — {}", error.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    /**
     * Retorna emoji correspondente à severidade do Zabbix.
     *
     * @param severity Nível de severidade
     * @return Emoji correspondente
     */
    private String getSeverityEmoji(String severity) {
        return switch (severity.toLowerCase()) {
            case "disaster"  -> "💀";
            case "high"      -> "🔴";
            case "average"   -> "🟠";
            case "warning"   -> "🟡";
            default          -> "⚪";
        };
    }
}
