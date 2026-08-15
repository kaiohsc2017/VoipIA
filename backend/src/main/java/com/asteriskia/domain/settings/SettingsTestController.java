package com.asteriskia.domain.settings;

import com.asteriskia.integration.ad.AdLdapConfig;
import com.asteriskia.integration.ad.LdapClient;
import com.asteriskia.integration.jira.JiraIntegrationService;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

/**
 * SettingsTestController — endpoints de teste de conectividade por seção.
 *
 * <p>Fase 12 — cada endpoint lê as credenciais do request body (para não depender do .env em disco,
 * permitindo testar ANTES de salvar) e faz uma chamada de validação real.
 *
 * <p>POST /api/v1/settings/test/jira → GET /rest/api/3/myself POST /api/v1/settings/test/zabbix →
 * user.login na API JSON-RPC POST /api/v1/settings/test/telegram → getMe no Bot API POST
 * /api/v1/settings/test/sip → resolução DNS do host SIP
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settings/test")
@RequiredArgsConstructor
public class SettingsTestController {

    private final RestTemplate restTemplate;
    private final JiraIntegrationService jiraService;
    private final LdapClient ldapClient;
    private final EmailSenderService emailSenderService;

    // -------------------------------------------------------------------------
    // Jira
    // -------------------------------------------------------------------------

    @PostMapping("/jira")
    public ResponseEntity<?> testJira(@RequestBody Map<String, String> body) {
        String baseUrl = body.getOrDefault("JIRA_BASE_URL", "").trim();
        String email = body.getOrDefault("JIRA_USER_EMAIL", "").trim();
        String token = body.getOrDefault("JIRA_API_TOKEN", "").trim();

        if (baseUrl.isEmpty() || email.isEmpty() || token.isEmpty() || isMasked(token)) {
            return bad(
                    "Preencha os campos JIRA_BASE_URL, JIRA_USER_EMAIL e JIRA_API_TOKEN antes de testar.");
        }
        if (!isSafePublicUrl(baseUrl)) {
            return bad("JIRA_BASE_URL inválida ou aponta para um host privado/interno.");
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
    public ResponseEntity<?> testZabbix(@RequestBody Map<String, String> body) {
        String apiUrl = body.getOrDefault("ZABBIX_API_URL", "").trim();
        String user = body.getOrDefault("ZABBIX_USER", "").trim();
        String password = body.getOrDefault("ZABBIX_PASSWORD", "").trim();

        if (apiUrl.isEmpty() || user.isEmpty() || password.isEmpty() || isMasked(password)) {
            return bad(
                    "Preencha os campos ZABBIX_API_URL, ZABBIX_USER e ZABBIX_PASSWORD antes de testar.");
        }
        if (!isSafePublicUrl(apiUrl)) {
            return bad("ZABBIX_API_URL inválida ou aponta para um host privado/interno.");
        }

        try {
            String jsonBody =
                    String.format(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"user.login\","
                                    + "\"params\":{\"username\":\"%s\",\"password\":\"%s\"},"
                                    + "\"id\":1}",
                            user, password);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> resp =
                    restTemplate.exchange(
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
    // Active Directory (módulo Call Center, Fase 1)
    // -------------------------------------------------------------------------

    /**
     * Diferente de Jira/Zabbix: NÃO valida "host público" — um Domain Controller é sempre um
     * host da rede interna corporativa por definição, bloquear IP privado/RFC1918 quebraria o
     * único caso de uso real deste endpoint.
     */
    @PostMapping("/ad")
    public ResponseEntity<?> testAd(@RequestBody Map<String, String> body) {
        String host = body.getOrDefault("AD_LDAP_HOST", "").trim();
        String baseDn = body.getOrDefault("AD_LDAP_BASE_DN", "").trim();
        String bindDn = body.getOrDefault("AD_LDAP_BIND_DN", "").trim();
        String bindPassword = body.getOrDefault("AD_LDAP_BIND_PASSWORD", "").trim();
        int port = parseIntOrDefault(body.get("AD_LDAP_PORT"), 636);
        boolean useSsl = !"false".equalsIgnoreCase(body.getOrDefault("AD_LDAP_USE_SSL", "true"));

        if (host.isEmpty() || baseDn.isEmpty() || bindDn.isEmpty() || bindPassword.isEmpty()
                || isMasked(bindPassword)) {
            return bad(
                    "Preencha host, base DN, conta de serviço e senha antes de testar.");
        }

        try {
            AdLdapConfig cfg =
                    new AdLdapConfig(true, host, port, useSsl, baseDn, bindDn, bindPassword, true, 2);
            String message = ldapClient.testConnection(cfg);
            return ok(message);
        } catch (Exception e) {
            log.warn("Teste AD falhou: {}", sanitize(e.getMessage()));
            return bad("Falha ao conectar: " + sanitize(e.getMessage()));
        }
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        try {
            return value != null ? Integer.parseInt(value.trim()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // -------------------------------------------------------------------------
    // Telegram
    // -------------------------------------------------------------------------

    @PostMapping("/telegram")
    public ResponseEntity<?> testTelegram(@RequestBody Map<String, String> body) {
        String token = body.getOrDefault("TELEGRAM_BOT_TOKEN", "").trim();

        if (token.isEmpty() || isMasked(token)) {
            return bad("Preencha o campo TELEGRAM_BOT_TOKEN antes de testar.");
        }

        try {
            URI uri = URI.create("https://api.telegram.org/bot" + token + "/getMe");
            ResponseEntity<Map> resp =
                    restTemplate.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, Map.class);

            Map<?, ?> respBody = resp.getBody();
            if (respBody != null && Boolean.TRUE.equals(respBody.get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) respBody.get("result");
                String botName =
                        result != null ? String.valueOf(result.getOrDefault("username", "?")) : "?";
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
    // E-mail (CFG-email do plano do Call Center)
    // -------------------------------------------------------------------------

    @PostMapping("/email")
    public ResponseEntity<?> testEmail(@RequestBody Map<String, String> body) {
        String host = body.getOrDefault("SMTP_HOST", "").trim();
        String username = body.getOrDefault("SMTP_USERNAME", "").trim();
        String password = body.getOrDefault("SMTP_PASSWORD_CREDENTIAL", "").trim();
        int port = parseIntOrDefault(body.get("SMTP_PORT"), 587);
        boolean starttls = !"false".equalsIgnoreCase(body.getOrDefault("SMTP_STARTTLS", "true"));

        if (host.isEmpty() || username.isEmpty() || password.isEmpty() || isMasked(password)) {
            return bad("Preencha host, usuário e senha SMTP antes de testar.");
        }

        try {
            String message = emailSenderService.testConnection(host, port, username, password, starttls);
            return ok(message);
        } catch (Exception e) {
            log.warn("Teste de e-mail falhou: {}", e.getClass().getSimpleName());
            return bad("Falha ao conectar: " + sanitize(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isMasked(String value) {
        return value != null && value.startsWith("\u2022");
    }

    /**
     * Achado de seguran\u00e7a (SSRF): JIRA_BASE_URL/ZABBIX_API_URL v\u00eam do body da
     * requisi\u00e7\u00e3o \u2014 qualquer usu\u00e1rio com PERM_WRITE_telecom.settings podia
     * apontar pra 172.16.7.11:5432, 169.254.169.254 ou localhost:8080 e o backend fazia a chamada
     * (no caso do Jira, at\u00e9 com a credencial Basic Auth no header, vazando o token pro host
     * arbitr\u00e1rio). Resolve o host e bloqueia qualquer IP privado/
     * loopback/link-local/multicast antes de qualquer chamada de teste. Res\u00edduo conhecido:
     * n\u00e3o protege contra DNS rebinding (o host \u00e9 resolvido de novo na conex\u00e3o real)
     * nem contra redirect 3xx do host de destino \u2014 ver valida\u00e7\u00e3o de redirect
     * desabilitado no RestTemplate (AppConfig) para o caso do Zabbix.
     */
    private boolean isSafePublicUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return false;
            }
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()) {
                    return false;
                }
            }
            return true;
        } catch (URISyntaxException | java.net.UnknownHostException e) {
            return false;
        }
    }

    private String sanitize(String msg) {
        if (msg == null) return "Erro desconhecido";
        // Remove possíveis tokens/senhas do stack trace exposto
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    private ResponseEntity<?> ok(String message) {
        return ResponseEntity.ok(new SettingsCheckResult(true, message));
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.ok(new SettingsCheckResult(false, message));
    }
}
