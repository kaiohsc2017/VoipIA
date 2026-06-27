package com.asteriskia.domain.settings;

import com.asteriskia.integration.jira.JiraIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;

/**
 * SettingsTestController — endpoints de teste de conectividade por seção.
 *
 * Fase 12 — cada endpoint lê as credenciais do request body (para não
 * depender do .env em disco, permitindo testar ANTES de salvar) e faz
 * uma chamada de validação real.
 *
 * POST /api/v1/settings/test/jira     → GET /rest/api/3/myself
 * POST /api/v1/settings/test/zabbix   → user.login na API JSON-RPC
 * POST /api/v1/settings/test/telegram → getMe no Bot API
 * POST /api/v1/settings/test/sip      → resolução DNS do host SIP
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settings/test")
@RequiredArgsConstructor
@Tag(name = "Settings — Testes", description = "Testes de conectividade por seção de configurações")
public class SettingsTestController {

    private final RestTemplate          restTemplate;
    private final JiraIntegrationService jiraService;

    // -------------------------------------------------------------------------
    // Jira
    // -------------------------------------------------------------------------

    @PostMapping("/jira")
    @Operation(summary = "Testa conectividade com o Jira Cloud usando as credenciais fornecidas")
    public ResponseEntity<?> testJira(@RequestBody Map<String, String> body) {
        String baseUrl = body.getOrDefault("JIRA_BASE_URL", "").trim();
        String email   = body.getOrDefault("JIRA_USER_EMAIL", "").trim();
        String token   = body.getOrDefault("JIRA_API_TOKEN", "").trim();

        if (baseUrl.isEmpty() || email.isEmpty() || token.isEmpty() || isMasked(token)) {
            return bad("Preencha os campos JIRA_BASE_URL, JIRA_USER_EMAIL e JIRA_API_TOKEN antes de testar.");
        }

        try {
            // Usa o mesmo WebClient + headers da integração real (API v3)
            String displayName = jiraService.testConnection(baseUrl, email, token);
            return ok("Conectado ao Jira! Usuário: " + displayName);
        } catch (Exception e) {
            log.warn("Teste Jira falhou: {}", e.getMessage());
            return bad("Falha ao conectar: " + sanitize(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Zabbix
    // -------------------------------------------------------------------------

    @PostMapping("/zabbix")
    @Operation(summary = "Testa conectividade com o Zabbix usando as credenciais fornecidas")
    public ResponseEntity<?> testZabbix(@RequestBody Map<String, String> body) {
        String apiUrl   = body.getOrDefault("ZABBIX_API_URL", "").trim();
        String user     = body.getOrDefault("ZABBIX_USER", "").trim();
        String password = body.getOrDefault("ZABBIX_PASSWORD", "").trim();

        if (apiUrl.isEmpty() || user.isEmpty() || password.isEmpty() || isMasked(password)) {
            return bad("Preencha os campos ZABBIX_API_URL, ZABBIX_USER e ZABBIX_PASSWORD antes de testar.");
        }

        try {
            String jsonBody = String.format(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"user.login\"," +
                    "\"params\":{\"username\":\"%s\",\"password\":\"%s\"}," +
                    "\"id\":1}", user, password);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    URI.create(apiUrl),
                    HttpMethod.POST,
                    new HttpEntity<>(jsonBody, headers),
                    Map.class);

            Map<?, ?> respBody = resp.getBody();
            if (respBody != null && respBody.containsKey("result")) {
                return ok("Conectado ao Zabbix com sucesso! Token de sessão obtido.");
            } else if (respBody != null && respBody.containsKey("error")) {
                Object err = respBody.get("error");
                return bad("Zabbix retornou erro: " + err);
            }
            return bad("Resposta inesperada do Zabbix.");
        } catch (Exception e) {
            log.warn("Teste Zabbix falhou: {}", e.getMessage());
            return bad("Falha ao conectar: " + sanitize(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Telegram
    // -------------------------------------------------------------------------

    @PostMapping("/telegram")
    @Operation(summary = "Testa o Bot Token do Telegram chamando getMe")
    public ResponseEntity<?> testTelegram(@RequestBody Map<String, String> body) {
        String token = body.getOrDefault("TELEGRAM_BOT_TOKEN", "").trim();

        if (token.isEmpty() || isMasked(token)) {
            return bad("Preencha o campo TELEGRAM_BOT_TOKEN antes de testar.");
        }

        try {
            URI uri = URI.create("https://api.telegram.org/bot" + token + "/getMe");
            ResponseEntity<Map> resp = restTemplate.exchange(
                    uri, HttpMethod.GET, HttpEntity.EMPTY, Map.class);

            Map<?, ?> respBody = resp.getBody();
            if (respBody != null && Boolean.TRUE.equals(respBody.get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) respBody.get("result");
                String botName = result != null ? String.valueOf(result.getOrDefault("username", "?")) : "?";
                return ok("Bot Telegram conectado! Username: @" + botName);
            }
            return bad("Telegram retornou ok=false. Verifique o token.");
        } catch (Exception e) {
            log.warn("Teste Telegram falhou: {}", e.getMessage());
            return bad("Falha ao conectar: " + sanitize(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // SIP (resolução DNS)
    // -------------------------------------------------------------------------

    @PostMapping("/sip")
    @Operation(summary = "Verifica se o host do tronco SIP resolve via DNS")
    public ResponseEntity<?> testSip(@RequestBody Map<String, String> body) {
        String host = body.getOrDefault("SIP_TRUNK_HOST", "").trim();

        if (host.isEmpty()) {
            return bad("Preencha o campo SIP_TRUNK_HOST antes de testar.");
        }

        try {
            InetAddress addr = InetAddress.getByName(host);
            return ok("Host SIP resolvido: " + host + " → " + addr.getHostAddress());
        } catch (Exception e) {
            log.warn("Teste SIP DNS falhou: {}", e.getMessage());
            return bad("Não foi possível resolver o host \"" + host + "\": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isMasked(String value) {
        return value != null && value.startsWith("\u2022");
    }

    private String sanitize(String msg) {
        if (msg == null) return "Erro desconhecido";
        // Remove possíveis tokens/senhas do stack trace exposto
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    private ResponseEntity<?> ok(String message) {
        return ResponseEntity.ok(new TestResult(true, message));
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.ok(new TestResult(false, message));
    }

    public record TestResult(boolean success, String message) {}
}
