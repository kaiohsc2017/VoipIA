package com.asteriskia.integration.jira;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * Cria issues no Jira a partir das respostas coletadas pela URA.
 * Documentação: https://developer.atlassian.com/cloud/jira/platform/rest/v3/
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraIntegrationService {

    @Value("${app.jira.base-url}")
    private String jiraBaseUrl;

    @Value("${app.jira.user-email}")
    private String userEmail;

    @Value("${app.jira.api-token}")
    private String apiToken;

    @Value("${app.jira.project-key}")
    private String projectKey;

    private final WebClient.Builder webClientBuilder;

    /**
     * Cria um issue no Jira com os campos coletados pela URA.
     *
     * @param fields Mapa de campos: chave Jira → valor coletado via STT
     * @return Chave do issue criado (ex: "PROJ-1234") ou null em caso de erro
     */
    public String createIssue(Map<String, String> fields) {
        try {
            Map<String, Object> issueFields = buildIssueFields(fields);
            Map<String, Object> body = Map.of("fields", issueFields);

            String authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString((userEmail + ":" + apiToken).getBytes());

            Map<?, ?> response = webClientBuilder.build()
                    .post()
                    .uri(jiraBaseUrl + "/rest/api/3/issue")
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .onErrorResume(e -> {
                        log.error("Jira API error: {}", e.getMessage());
                        return Mono.empty();
                    })
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

    // ---------------------------------------------------------------------------
    // Privado
    // ---------------------------------------------------------------------------

    /**
     * Monta o payload de campos para a criação do issue no Jira.
     * Campos customizados são passados pelo jira_field_key das perguntas da URA.
     */
    private Map<String, Object> buildIssueFields(Map<String, String> fields) {
        Map<String, Object> issueFields = new HashMap<>();

        // Campos obrigatórios
        issueFields.put("project", Map.of("key", projectKey));
        issueFields.put("issuetype", Map.of("name", "Support"));
        issueFields.put("summary", buildSummary(fields));

        // Descrição principal (campo "description" na URA)
        String description = fields.getOrDefault("description", "Chamado aberto via URA AsteriskIA.");
        issueFields.put("description", buildAdfText(description));

        // Prioridade
        String priority = fields.getOrDefault("priority", "Média");
        issueFields.put("priority", Map.of("name", mapPriority(priority)));

        // Campos customizados da URA (prefixo "customfield_")
        fields.forEach((key, value) -> {
            if (key.startsWith("customfield_")) {
                issueFields.put(key, value);
            }
        });

        return issueFields;
    }

    private String buildSummary(Map<String, String> fields) {
        String clientName = fields.getOrDefault("customfield_nome_cliente", "Cliente");
        return "Chamado URA — " + clientName;
    }

    /**
     * Jira Cloud API v3 usa Atlassian Document Format (ADF) para campos de texto longo.
     */
    private Map<String, Object> buildAdfText(String text) {
        return Map.of(
                "version", 1,
                "type", "doc",
                "content", java.util.List.of(
                        Map.of(
                                "type", "paragraph",
                                "content", java.util.List.of(
                                        Map.of("type", "text", "text", text)
                                )
                        )
                )
        );
    }

    /** Mapeia as prioridades em PT-BR para os valores do Jira. */
    private String mapPriority(String priority) {
        return switch (priority.toLowerCase().trim()) {
            case "alta", "high", "urgente" -> "High";
            case "baixa", "low" -> "Low";
            default -> "Medium";
        };
    }
}
