package com.asteriskia.integration.zabbix;

import com.asteriskia.domain.alert.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ZabbixPollingService — Polling periódico da API Zabbix para detecção de incidentes críticos.
 *
 * A cada N minutos (configurado em ZABBIX_POLL_INTERVAL_MINUTES), consulta a API JSON-RPC do Zabbix
 * e para cada trigger com severidade >= mínima que ainda está ativa, dispara uma chamada de alerta.
 *
 * Documentação API Zabbix: https://www.zabbix.com/documentation/current/en/manual/api
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class ZabbixPollingService {

    @Value("${app.zabbix.api-url}")
    private String zabbixApiUrl;

    @Value("${app.zabbix.user}")
    private String zabbixUser;

    @Value("${app.zabbix.password}")
    private String zabbixPassword;

    @Value("${app.zabbix.min-severity:4}")
    private int minSeverity;

    private final WebClient.Builder webClientBuilder;
    private final AlertService alertService;

    /** Cache de trigger IDs já processados nesta sessão (evita duplicidade). */
    private final Set<String> processedTriggers = new HashSet<>();

    /** Token de autenticação Zabbix (renovado periodicamente). */
    private String authToken;

    /**
     * Executa o polling a cada N minutos.
     * fixedDelayString permite configurar via application.properties.
     */
    @Scheduled(fixedDelayString = "${app.zabbix.poll-interval-minutes:5}",
               timeUnit = TimeUnit.MINUTES,
               initialDelay = 1)
    public void pollZabbix() {
        log.debug("Iniciando polling Zabbix...");
        try {
            if (authToken == null) {
                authToken = authenticate();
            }
            if (authToken == null) {
                log.error("Zabbix: falha na autenticação — polling abortado");
                return;
            }

            List<Map<String, Object>> triggers = fetchActiveTriggers();
            log.info("Zabbix: {} triggers ativos com severidade >= {}", triggers.size(), minSeverity);

            for (Map<String, Object> trigger : triggers) {
                processTrigger(trigger);
            }

        } catch (Exception e) {
            log.error("Erro no polling Zabbix: {}", e.getMessage(), e);
            authToken = null; // Força re-autenticação no próximo ciclo
        }
    }

    // ---------------------------------------------------------------------------
    // Privado
    // ---------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String authenticate() {
        try {
            Map<String, Object> body = Map.of(
                    "jsonrpc", "2.0",
                    "method", "user.login",
                    "params", Map.of("username", zabbixUser, "password", zabbixPassword),
                    "id", 1
            );

            Map<?, ?> response = post(body);
            if (response != null && response.containsKey("result")) {
                String token = (String) response.get("result");
                log.info("Zabbix: autenticado com sucesso");
                return token;
            }
        } catch (Exception e) {
            log.error("Zabbix auth error: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchActiveTriggers() {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "trigger.get",
                "params", Map.of(
                        "output", List.of("triggerid", "description", "priority", "value"),
                        "selectHosts", List.of("host", "name"),
                        "filter", Map.of("value", 1), // 1 = trigger ativo/problem
                        "min_severity", minSeverity,
                        "sortfield", "priority",
                        "sortorder", "DESC",
                        "limit", 50
                ),
                "auth", authToken,
                "id", 2
        );

        Map<?, ?> response = post(body);
        if (response != null && response.containsKey("result")) {
            return (List<Map<String, Object>>) response.get("result");
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private void processTrigger(Map<String, Object> trigger) {
        String triggerId = (String) trigger.get("triggerid");
        if (processedTriggers.contains(triggerId)) {
            return; // Já processado nesta sessão
        }

        String description = (String) trigger.getOrDefault("description", "Incidente desconhecido");
        int priority = Integer.parseInt(trigger.getOrDefault("priority", "4").toString());
        String severity = mapSeverity(priority);

        // Extrai nome do host
        List<Map<String, Object>> hosts = (List<Map<String, Object>>) trigger.get("hosts");
        String hostName = (hosts != null && !hosts.isEmpty())
                ? (String) hosts.get(0).getOrDefault("name", "Desconhecido")
                : "Desconhecido";

        log.info("Zabbix: novo incidente detectado — trigger={} host={} severity={}", triggerId, hostName, severity);

        alertService.triggerAlert(triggerId, description, severity, hostName);
        processedTriggers.add(triggerId);
    }

    private Map<?, ?> post(Map<String, Object> body) {
        return webClientBuilder.build()
                .post()
                .uri(zabbixApiUrl)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of())
                .block();
    }

    private String mapSeverity(int priority) {
        return switch (priority) {
            case 5 -> "Disaster";
            case 4 -> "High";
            case 3 -> "Average";
            case 2 -> "Warning";
            case 1 -> "Information";
            default -> "Unknown";
        };
    }
}
