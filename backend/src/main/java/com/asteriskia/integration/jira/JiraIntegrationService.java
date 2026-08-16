package com.asteriskia.integration.jira;

import com.asteriskia.domain.config.ConfigService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * JiraIntegrationService — Integração com Jira Cloud REST API v3.
 *
 * <p>Referência: https://developer.atlassian.com/cloud/jira/platform/rest/v3/
 *
 * <p>Autenticação: Basic Auth com e-mail + API Token (Base64). Configurações lidas dinamicamente
 * via ConfigService (banco de dados) — alterações na tela de Settings refletem sem restart de
 * container (TTL 60s).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraIntegrationService {

    private final ConfigService config;
    private final WebClient.Builder webClientBuilder;

    /** Timeout para chamadas à API do Jira. */
    private static final Duration JIRA_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Cria uma issue no Jira com os campos coletados pela URA.
     *
     * @param fields Mapa de campos (jira_field_key → valor string). Campos iniciados com
     *     "customfield_" são incluídos diretamente.
     * @return Chave da issue criada (ex: "SUP-42"), ou null em caso de falha.
     */
    public String createIssue(Map<String, String> fields) {
        String baseUrl = config.get("JIRA_BASE_URL");
        String userEmail = config.get("JIRA_USER_EMAIL");
        String apiToken = config.get("JIRA_API_TOKEN");
        String projectKey = config.get("JIRA_PROJECT_KEY");
        String issueType = config.get("JIRA_ISSUE_TYPE");

        if (baseUrl.isBlank()
                || userEmail.isBlank()
                || apiToken.isBlank()
                || projectKey.isBlank()) {
            log.warn("Jira não configurado — pulando criação de issue (verifique Settings → Jira)");
            return null;
        }

        // Usa "Task" como fallback se JIRA_ISSUE_TYPE não estiver configurado.
        // "Task" existe em praticamente todos os projetos Jira Cloud.
        if (issueType == null || issueType.isBlank()) {
            issueType = "Task";
        }

        try {
            String authHeader = buildAuthHeader(userEmail, apiToken);
            String endpoint = normalizeBaseUrl(baseUrl) + "/rest/api/3/issue";

            Map<String, Object> body =
                    Map.of("fields", buildIssueFields(fields, projectKey, issueType));

            Map<?, ?> response =
                    webClientBuilder
                            .build()
                            .post()
                            .uri(endpoint)
                            .header(HttpHeaders.AUTHORIZATION, authHeader)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                            // X-Atlassian-Token: no-check — necessário para contornar XSRF em
                            // algumas instâncias
                            .header("X-Atlassian-Token", "no-check")
                            .bodyValue(body)
                            .retrieve()
                            // Trata erros HTTP de forma explícita (4xx/5xx)
                            .onStatus(
                                    HttpStatusCode::isError,
                                    clientResponse ->
                                            clientResponse
                                                    .bodyToMono(String.class)
                                                    .flatMap(
                                                            errorBody -> {
                                                                log.error(
                                                                        "Jira API erro {}: {}",
                                                                        clientResponse.statusCode(),
                                                                        errorBody);
                                                                return Mono.error(
                                                                        new RuntimeException(
                                                                                "Jira HTTP "
                                                                                        + clientResponse
                                                                                                .statusCode()
                                                                                        + ": "
                                                                                        + errorBody));
                                                            }))
                            .bodyToMono(Map.class)
                            .timeout(JIRA_TIMEOUT)
                            .onErrorResume(
                                    e -> {
                                        log.error(
                                                "Erro ao criar issue no Jira: {}", e.getMessage());
                                        return Mono.empty();
                                    })
                            .block();

            if (response != null && response.containsKey("key")) {
                String issueKey = (String) response.get("key");
                log.info("Jira issue criado: {}", issueKey);
                return issueKey;
            }

            log.warn("Jira retornou resposta sem campo 'key': {}", response);
        } catch (Exception e) {
            log.error("Erro inesperado ao criar issue no Jira: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Valida as credenciais chamando GET /rest/api/3/myself. Retorna o displayName do usuário
     * autenticado, ou lança exceção em caso de falha.
     */
    public String testConnection(String baseUrl, String userEmail, String apiToken) {
        String endpoint = normalizeBaseUrl(baseUrl) + "/rest/api/3/myself";

        Map<?, ?> resp =
                webClientBuilder
                        .build()
                        .get()
                        .uri(endpoint)
                        .header(HttpHeaders.AUTHORIZATION, buildAuthHeader(userEmail, apiToken))
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .header("X-Atlassian-Token", "no-check")
                        .retrieve()
                        .onStatus(
                                HttpStatusCode::isError,
                                clientResponse ->
                                        clientResponse
                                                .bodyToMono(String.class)
                                                .flatMap(
                                                        body ->
                                                                Mono.error(
                                                                        new RuntimeException(
                                                                                "HTTP "
                                                                                        + clientResponse
                                                                                                .statusCode()
                                                                                        + ": "
                                                                                        + body))))
                        .bodyToMono(Map.class)
                        .timeout(JIRA_TIMEOUT)
                        .block();

        if (resp == null || !resp.containsKey("displayName")) {
            throw new RuntimeException("Resposta inesperada do Jira (sem displayName)");
        }
        return (String) resp.get("displayName");
    }

    /**
     * Busca status e resolução atuais de uma issue já criada — usado pelo JiraSyncScheduler para
     * manter jira_issue_status/jira_resolution em dia. Fail soft: retorna Optional vazio em
     * qualquer erro (não configurado, timeout, HTTP de erro), nunca lança para o chamador.
     */
    public Optional<JiraStatusInfo> fetchIssueStatus(String issueKey) {
        String baseUrl = config.get("JIRA_BASE_URL");
        String userEmail = config.get("JIRA_USER_EMAIL");
        String apiToken = config.get("JIRA_API_TOKEN");

        if (baseUrl.isBlank() || userEmail.isBlank() || apiToken.isBlank()) {
            return Optional.empty();
        }

        try {
            String endpoint =
                    normalizeBaseUrl(baseUrl)
                            + "/rest/api/3/issue/"
                            + issueKey
                            + "?fields=status,resolution";

            Map<?, ?> resp =
                    webClientBuilder
                            .build()
                            .get()
                            .uri(endpoint)
                            .header(HttpHeaders.AUTHORIZATION, buildAuthHeader(userEmail, apiToken))
                            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                            .header("X-Atlassian-Token", "no-check")
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::isError,
                                    clientResponse ->
                                            clientResponse
                                                    .bodyToMono(String.class)
                                                    .flatMap(
                                                            errorBody -> {
                                                                log.warn(
                                                                        "Jira fetchIssueStatus {} erro {}: {}",
                                                                        issueKey,
                                                                        clientResponse.statusCode(),
                                                                        errorBody);
                                                                return Mono.error(
                                                                        new RuntimeException(
                                                                                "Jira HTTP "
                                                                                        + clientResponse
                                                                                                .statusCode()
                                                                                        + ": "
                                                                                        + errorBody));
                                                            }))
                            .bodyToMono(Map.class)
                            .timeout(JIRA_TIMEOUT)
                            .onErrorResume(
                                    e -> {
                                        log.warn(
                                                "Erro ao consultar status da issue {} no Jira: {}",
                                                issueKey,
                                                e.getMessage());
                                        return Mono.empty();
                                    })
                            .block();

            if (resp == null) return Optional.empty();

            Map<?, ?> fields = (Map<?, ?>) resp.get("fields");
            if (fields == null) return Optional.empty();

            Map<?, ?> status = (Map<?, ?>) fields.get("status");
            Map<?, ?> resolution = (Map<?, ?>) fields.get("resolution");

            String statusName = status != null ? (String) status.get("name") : null;
            String resolutionName = resolution != null ? (String) resolution.get("name") : null;

            return Optional.of(new JiraStatusInfo(statusName, resolutionName));
        } catch (Exception e) {
            log.warn(
                    "Erro inesperado ao consultar status da issue {} no Jira: {}",
                    issueKey,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** Status + resolução de uma issue Jira. resolutionName é null enquanto não resolvida. */
    public record JiraStatusInfo(String statusName, String resolutionName) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Monta os campos da issue seguindo a estrutura exigida pela API v3.
     *
     * <p>Campos customizados cujos valores são strings simples são enviados diretamente. Para
     * campos select/multi-select, o chamador deve prefixar a chave com "customfield_" e passar o
     * valor como "option:<label>" para que seja convertido para {"value": "<label>"}.
     */
    private Map<String, Object> buildIssueFields(
            Map<String, String> fields, String projectKey, String issueType) {

        Map<String, Object> issueFields = new HashMap<>();

        // Campos obrigatórios
        issueFields.put("project", Map.of("key", projectKey));
        issueFields.put("issuetype", Map.of("name", issueType));
        issueFields.put("summary", buildSummary(fields));

        // Descrição em Atlassian Document Format (ADF) — exigido pela API v3
        String descText = fields.getOrDefault("description", "Chamado aberto via URA VoipIA.");
        issueFields.put("description", buildAdf(descText));

        // Prioridade (mapeada de português para os valores aceitos pelo Jira)
        String priority = mapPriority(fields.getOrDefault("priority", "Média"));
        issueFields.put("priority", Map.of("name", priority));

        // Labels — identifica chamados originados pela URA para facilitar filtros
        issueFields.put("labels", List.of("ura-asteriskia"));

        // Campos customizados enviados pela URA
        fields.forEach(
                (key, value) -> {
                    if (!key.startsWith("customfield_")) return;
                    if (value == null || value.isBlank()) return;

                    if (value.startsWith("option:")) {
                        // Campo select: {"value": "<label>"}
                        issueFields.put(key, Map.of("value", value.substring(7)));
                    } else {
                        // Campo texto/número: valor direto
                        issueFields.put(key, value);
                    }
                });

        return issueFields;
    }

    private String buildSummary(Map<String, String> fields) {
        String cliente = fields.getOrDefault("customfield_nome_cliente", "");
        return cliente.isBlank() ? "Chamado URA — VoipIA" : "Chamado URA — " + cliente;
    }

    /**
     * Constrói um documento ADF (Atlassian Document Format) de parágrafo simples. A API v3 do Jira
     * rejeita description como string plana — exige ADF.
     *
     * <p>Spec: https://developer.atlassian.com/cloud/jira/platform/apis/document/structure/
     */
    private Map<String, Object> buildAdf(String text) {
        return Map.of(
                "version",
                1,
                "type",
                "doc",
                "content",
                List.of(
                        Map.of(
                                "type",
                                "paragraph",
                                "content",
                                List.of(Map.of("type", "text", "text", text)))));
    }

    private String mapPriority(String priority) {
        return switch (priority.toLowerCase().trim()) {
            case "alta", "high", "urgente", "crítica", "critica" -> "High";
            case "baixa", "low" -> "Low";
            case "média", "media", "normal", "médio", "medio" -> "Medium";
            default -> "Medium";
        };
    }

    /**
     * Monta o header Basic Auth conforme a documentação da API v3. Formato: Base64(email:apiToken)
     * — não usar usuário/senha, apenas API Token.
     */
    private String buildAuthHeader(String userEmail, String apiToken) {
        String credentials = userEmail + ":" + apiToken;
        return "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** Remove trailing slash da base URL para evitar double-slash nas URIs. */
    private String normalizeBaseUrl(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}
