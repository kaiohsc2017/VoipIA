package com.asteriskia.domain.settings;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * AsteriskConfigController — Edição dos arquivos de configuração do Asterisk via UI.
 *
 * Gerencia dois arquivos que ficam em /etc/asterisk (montado via volume):
 *   - pjsip.conf.template  → tronco SIP (seção [tronco-sip])
 *   - extensions.conf      → plano de discagem (rotas de entrada e saída)
 *
 * GET  /api/v1/asterisk-config/tronco      → bloco [tronco-sip] atual
 * POST /api/v1/asterisk-config/tronco      → salva bloco + reload res_pjsip via AMI
 * GET  /api/v1/asterisk-config/rotas       → conteúdo completo do extensions.conf
 * POST /api/v1/asterisk-config/rotas       → salva extensions.conf + reload dialplan via AMI
 *
 * O reload via AMI é best-effort: falha de conexão retorna status descritivo
 * mas não impede o salvamento do arquivo.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/asterisk-config")
@RequiredArgsConstructor
public class AsteriskConfigController {

    private final SettingsService settingsService;
    private final AuditService    auditService;

    @Value("${app.asterisk.config-dir:/etc/asterisk}")
    private String configDir;

    @Value("${app.asterisk.ami.host:asterisk}")
    private String amiHost;

    @Value("${app.asterisk.ami.port:5038}")
    private int amiPort;

    @Value("${app.asterisk.ami.user:asteriskia}")
    private String amiUser;

    @Value("${app.asterisk.ami.password}")
    private String amiPassword;

    private static final int AMI_TIMEOUT_MS = 8_000;

    /** Campos pjsip conhecidos → variáveis .env correspondentes. */
    private static final Map<String, String> PJSIP_TO_ENV = Map.of(
            "host",        "SIP_TRUNK_HOST",
            "username",    "SIP_TRUNK_USER",
            "password",    "SIP_TRUNK_PASSWORD",
            "from_domain", "SIP_TRUNK_FROM_DOMAIN"
    );

    // =========================================================================
    // TRONCO SIP — pjsip.conf.template
    // =========================================================================

    @GetMapping("/tronco")
        public ResponseEntity<?> getTronco() {
        try {
            String template = readFile("pjsip.conf.template");
            String block    = extractSection(template, "tronco-sip");
            return ResponseEntity.ok(Map.of("block", block));
        } catch (IOException e) {
            log.error("Erro ao ler pjsip.conf.template: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao ler pjsip.conf.template: " + e.getMessage()));
        }
    }

    @PostMapping("/tronco")
        public ResponseEntity<?> saveTronco(
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {

        String block = body.get("block");
        if (block == null || block.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Campo 'block' não pode ser vazio."));

        String normalized = block.strip();
        try {
            // 1. Substitui seção no template
            String template    = readFile("pjsip.conf.template");
            String newTemplate = replaceSection(template, "tronco-sip", normalized);
            writeFile("pjsip.conf.template", newTemplate);

            // 2. Atualiza .env com campos mapeados
            Map<String, String> envUpdates = extractEnvFromPjsip(normalized);
            if (!envUpdates.isEmpty()) {
                settingsService.writeSettings(envUpdates,
                        auth != null ? auth.getName() : "admin", extractIp(request));
            }

            // 3. Reload PJSIP via AMI
            String reloadStatus = amiReload("module reload res_pjsip");

            auditService.log(request, "ASTERISK_TRONCO_SAVE",
                    "Bloco [tronco-sip] atualizado. AMI reload: " + reloadStatus, true);

            return ResponseEntity.ok(Map.of(
                    "message",      "Tronco SIP salvo com sucesso.",
                    "reloadStatus", reloadStatus,
                    "envKeys",      envUpdates.keySet()
            ));
        } catch (IOException e) {
            log.error("Erro ao salvar tronco SIP: {}", e.getMessage(), e);
            auditService.log(request, "ASTERISK_TRONCO_SAVE", "Falha: " + e.getMessage(), false);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao salvar: " + e.getMessage()));
        }
    }

    // =========================================================================
    // ROTAS — extensions.conf
    // =========================================================================

    @GetMapping("/rotas")
        public ResponseEntity<?> getRotas() {
        try {
            String content = readFile("extensions.conf");
            return ResponseEntity.ok(Map.of("content", content));
        } catch (IOException e) {
            log.error("Erro ao ler extensions.conf: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao ler extensions.conf: " + e.getMessage()));
        }
    }

    @PostMapping("/rotas")
        public ResponseEntity<?> saveRotas(
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {

        String content = body.get("content");
        if (content == null || content.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Campo 'content' não pode ser vazio."));

        try {
            writeFile("extensions.conf", content.strip() + "\n");

            String reloadStatus = amiReload("dialplan reload");

            auditService.log(request, "ASTERISK_ROTAS_SAVE",
                    "extensions.conf atualizado. AMI reload: " + reloadStatus, true);

            return ResponseEntity.ok(Map.of(
                    "message",      "Rotas salvas com sucesso.",
                    "reloadStatus", reloadStatus
            ));
        } catch (IOException e) {
            log.error("Erro ao salvar extensions.conf: {}", e.getMessage(), e);
            auditService.log(request, "ASTERISK_ROTAS_SAVE", "Falha: " + e.getMessage(), false);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao salvar: " + e.getMessage()));
        }
    }

    // =========================================================================
    // I/O de arquivos
    // =========================================================================

    private String readFile(String filename) throws IOException {
        Path path = Path.of(configDir, filename);
        if (!Files.exists(path))
            throw new IOException("Arquivo não encontrado: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private void writeFile(String filename, String content) throws IOException {
        Path path = Path.of(configDir, filename);
        Path tmp  = Path.of(configDir, filename + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Arquivo {} atualizado em {}", filename, configDir);
    }

    // =========================================================================
    // Parse da seção pjsip
    // =========================================================================

    /**
     * Extrai do template a seção [sectionName] — captura do cabeçalho até o próximo
     * cabeçalho de seção ou fim de arquivo.
     */
    private String extractSection(String content, String sectionName) {
        // Faz match da seção exata (ex: [tronco-sip]) e para antes da próxima seção
        Pattern p = Pattern.compile(
                "(?m)^\\[" + Pattern.quote(sectionName) + "\\][^\\[]*",
                Pattern.DOTALL);
        Matcher m = p.matcher(content);
        return m.find() ? m.group().stripTrailing() : "";
    }

    /**
     * Substitui a seção [tronco-sip] no template pelo novo bloco.
     * Se a seção não existir, apenda ao final.
     */
    private String replaceSection(String template, String sectionName, String newBlock) {
        Pattern p = Pattern.compile(
                "(?m)^\\[" + Pattern.quote(sectionName) + "\\][^\\[]*",
                Pattern.DOTALL);
        Matcher m = p.matcher(template);
        if (m.find()) {
            return template.substring(0, m.start()) + newBlock + "\n\n"
                    + template.substring(m.end()).stripLeading();
        }
        return template.stripTrailing() + "\n\n" + newBlock + "\n";
    }

    /**
     * Lê linhas do bloco pjsip e extrai campos mapeados para variáveis .env.
     */
    private Map<String, String> extractEnvFromPjsip(String block) {
        Map<String, String> updates = new LinkedHashMap<>();
        for (String line : block.lines().toList()) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith(";") || t.startsWith("[")) continue;
            int eq = t.indexOf('=');
            if (eq < 1) continue;
            String field  = t.substring(0, eq).strip().toLowerCase();
            String value  = t.substring(eq + 1).strip();
            String envKey = PJSIP_TO_ENV.get(field);
            if (envKey != null && !value.isBlank())
                updates.put(envKey, value);
        }
        return updates;
    }

    // =========================================================================
    // AMI
    // =========================================================================

    private String amiReload(String command) {
        try (Socket socket = new Socket(amiHost, amiPort)) {
            socket.setSoTimeout(AMI_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            reader.readLine(); // banner

            sendAmi(writer, Map.of("Action","Login","Username",amiUser,"Secret",amiPassword));
            String login = readAmi(reader);
            if (!login.contains("Success")) {
                log.warn("AMI: falha autenticação para reload");
                return "ami_auth_failed";
            }

            sendAmi(writer, Map.of("Action","Command","Command", command));
            String resp = readAmi(reader);
            log.info("AMI [{}]: {}", command, resp.trim());

            sendAmi(writer, Map.of("Action","Logoff"));
            return resp.contains("Success") || resp.contains("Output") ? "ok" : "warn:" + resp.trim();

        } catch (SocketTimeoutException e) {
            log.warn("AMI: timeout — {}", command);
            return "ami_timeout";
        } catch (IOException e) {
            log.warn("AMI: erro — {}: {}", command, e.getMessage());
            return "ami_error";
        }
    }

    private void sendAmi(PrintWriter w, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n");
        w.print(sb); w.flush();
    }

    private String readAmi(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) break;
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private String extractIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }
}
