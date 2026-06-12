package com.asteriskia.integration.zabbix;

import com.asteriskia.domain.alert.AlertService;
import com.asteriskia.domain.config.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * ZabbixPollingService — Polling periódico da API Zabbix para detecção de incidentes.
 *
 * Configurações lidas dinamicamente via ConfigService (banco de dados).
 * Alterações na tela de Settings refletem sem restart de container (TTL 60s).
 *
 * Nota: o intervalo de polling fixedDelay é definido no boot e não muda em runtime
 * (limitação do @Scheduled). A severidade mínima e credenciais, porém, são dinâmicas.
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class ZabbixPollingService {

    private final ConfigService     config;
    private final WebClient.Builder webClientBuilder;
    private final AlertService      alertService;

    private final Set<String> processedTriggers = new HashSet<>();
    private String authToken;
    private String lastApiUrl; // detecta mudança de URL para forçar re-autenticação

    @Scheduled(fixedDelayString = "${app.zabbix.poll-interval-minutes:5}",
               timeUnit = TimeUnit.MINUTES,
               initialDelay = 1)
    public void pollZabbix() {
        String apiUrl   = config.get("ZABBIX_API_URL");
        String user     = config.get("ZABBIX_USER");
        String password = config.get("ZABBIX_PASSWORD");

        if (apiUrl.isBlank() || user.isBlank()) {
            log.debug("Zabbix não configurado — polling ignorado (verifique Settings → Zabbix)");
            return;
        }

        // Força re-autenticação se a URL ou credenciais mudaram
        if (!apiUrl.equals(lastApiUrl)) {
            log.info("Zabbix: URL alterada — forçando re-autenticação");
            authToken = null;
            lastApiUrl = apiUrl;
        }

        try {
            if (authToken == null) authToken = authenticate(apiUrl, user, password);
            if (authToken == null) { log.error("Zabbix: falha na autenticação — polling abortado"); return; }

            int minSeverity = config.getInt("ZABBIX_MIN_SEVERITY", 4);
            List<Map<String, Object>> triggers = fetchActiveTriggers(apiUrl, minSeverity);
            log.info("Zabbix: {} triggers ativos com severidade >= {}", triggers.size(), minSeverity);
            triggers.forEach(this::processTrigger);

        } catch (Exception e) {
            log.error("Erro no polling Zabbix: {}", e.getMessage(), e);
            authToken = null;
        }
    }

    @SuppressWarnings("unchecked")
    private String authenticate(String apiUrl, String user, String password) {
        try {
            Map<?, ?> response = post(apiUrl, Map.of(
                    "jsonrpc", "2.0", "method", "user.login",
                    "params", Map.of("username", user, "password", password), "id", 1));
            if (response != null && response.containsKey("result")) {
                log.info("Zabbix: autenticado com sucesso");
                return (String) response.get("result");
            }
        } catch (Exception e) {
            log.error("Zabbix auth error: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchActiveTriggers(String apiUrl, int minSeverity) {
        Map<?, ?> response = post(apiUrl, Map.of(
                "jsonrpc", "2.0", "method", "trigger.get",
                "params", Map.of(
                        "output", List.of("triggerid", "description", "priority", "value"),
                        "selectHosts", List.of("host", "name"),
                        "filter", Map.of("value", 1),
                        "min_severity", minSeverity,
                        "sortfield", "priority", "sortorder", "DESC", "limit", 50),
                "auth", authToken, "id", 2));
        if (response != null && response.containsKey("result"))
            return (List<Map<String, Object>>) response.get("result");
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private void processTrigger(Map<String, Object> trigger) {
        String triggerId = (String) trigger.get("triggerid");
        if (processedTriggers.contains(triggerId)) return;

        String description = (String) trigger.getOrDefault("description", "Incidente desconhecido");
        int    priority    = Integer.parseInt(trigger.getOrDefault("priority", "4").toString());
        String severity    = mapSeverity(priority);

        List<Map<String, Object>> hosts = (List<Map<String, Object>>) trigger.get("hosts");
        String hostName = (hosts != null && !hosts.isEmpty())
                ? (String) hosts.get(0).getOrDefault("name", "Desconhecido") : "Desconhecido";

        log.info("Zabbix: incidente — trigger={} host={} severity={}", triggerId, hostName, severity);
        alertService.triggerAlert(triggerId, description, severity, hostName);
        processedTriggers.add(triggerId);
    }

    private Map<?, ?> post(String apiUrl, Map<String, Object> body) {
        return webClientBuilder.build()
                .post().uri(apiUrl)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of())
                .block();
    }

    private String mapSeverity(int priority) {
        return switch (priority) {
            case 5 -> "Disaster"; case 4 -> "High"; case 3 -> "Average";
            case 2 -> "Warning";  case 1 -> "Information"; default -> "Unknown";
        };
    }
}
