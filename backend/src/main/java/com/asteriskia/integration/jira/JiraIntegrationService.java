package com.asteriskia.integration.jira;

import com.asteriskia.domain.config.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * JiraIntegrationService — Integração com Jira Cloud REST API v3.
 *
 * Configurações lidas dinamicamente via ConfigService (banco de dados).
 * Alterações na tela de Settings refletem sem restart de container (TTL 60s).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraIntegrationService {

    private final ConfigService      config;
    private final WebClient.Builder  webClientBuilder;

    public String createIssue(Map<String, String> fields) {
        // Lê configurações em runtime — sem @Value, sem restart
        String baseUrl    = config.get("JIRA_BASE_URL");
        String userEmail  = config.get("JIRA_USER_EMAIL");
        String apiToken   = config.get("JIRA_API_TOKEN");
        String projectKey = config.get("JIRA_PROJECT_KEY");

        if (baseUrl.isBlank() || userEmail.isBlank() || apiToken.isBlank() || projectKey.isBlank()) {
            log.warn("Jira não configurado — pulando criação de issue (verifique Settings → Jira)");
            return null;
        }

        try {
            Map<String, Object> body = Map.of("fields", buildIssueFields(fields, projectKey));
            String authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString((userEmail + ":" + apiToken).getBytes());

            Map<?, ?> response = webClientBuilder.build()
                    .post()
                    .uri(baseUrl + "/rest/api/3/issue")
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .onErrorResume(e -> { log.error("Jira API error: {}", e.getMessage()); return Mono.empty(); })
                    .block();

            if (response != null && response.containsKey("key")) {
                String issueKey = (String) response.get("key");
                log.info("Jira issue criado: {}", issueKey);
                return issueKey;
            }
        } catch (Exception e) {
            log.error("Erro ao criar issue no Jira: {}", e.getMessage(), e);
        }
        return null;
    }

    private Map<String, Object> buildIssueFields(Map<String, String> fields, String projectKey) {
        Map<String, Object> issueFields = new HashMap<>();
        issueFields.put("project",   Map.of("key", projectKey));
        issueFields.put("issuetype", Map.of("name", "Support"));
        issueFields.put("summary",   buildSummary(fields));
        issueFields.put("description", buildAdfText(
                fields.getOrDefault("description", "Chamado aberto via URA AsteriskIA.")));
        issueFields.put("priority", Map.of("name",
                mapPriority(fields.getOrDefault("priority", "Média"))));
        fields.forEach((key, value) -> {
            if (key.startsWith("customfield_")) issueFields.put(key, value);
        });
        return issueFields;
    }

    private String buildSummary(Map<String, String> fields) {
        return "Chamado URA — " + fields.getOrDefault("customfield_nome_cliente", "Cliente");
    }

    private Map<String, Object> buildAdfText(String text) {
        return Map.of("version", 1, "type", "doc",
                "content", java.util.List.of(Map.of("type", "paragraph",
                        "content", java.util.List.of(Map.of("type", "text", "text", text)))));
    }

    private String mapPriority(String priority) {
        return switch (priority.toLowerCase().trim()) {
            case "alta", "high", "urgente" -> "High";
            case "baixa", "low"            -> "Low";
            default                        -> "Medium";
        };
    }
}
