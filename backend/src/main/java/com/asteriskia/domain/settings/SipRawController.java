package com.asteriskia.domain.settings;

import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.integration.ami.AmiOriginateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.*;
import java.util.regex.*;

/**
 * SipRawController — Recebe o bloco de texto livre do [tronco-sip]
 * e aplica as configurações em dois lugares:
 *
 *   1. .env  → atualiza as variáveis SIP_TRUNK_* extraídas do bloco
 *   2. pjsip.conf.template → substitui apenas a seção [tronco-sip] pelo
 *      texto exato que o usuário colou
 *
 * Após salvar, dispara "module reload res_pjsip" via AMI para o Asterisk
 * absorver as mudanças sem precisar reiniciar o container.
 *
 * POST /api/v1/settings/sip-raw
 *   Body: { "block": "<texto livre>" }
 *
 * GET  /api/v1/settings/sip-raw
 *   Retorna o conteúdo atual da seção [tronco-sip] do template.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settings/sip-raw")
@RequiredArgsConstructor
@Tag(name = "SIP Raw", description = "Edição livre do bloco [tronco-sip] no pjsip.conf.template")
public class SipRawController {

    private final SettingsService settingsService;
    private final AuditService    auditService;

    @Value("${app.asterisk.config-dir:/opt/AsteriskIA/asterisk/config}")
    private String asteriskConfigDir;

    @Value("${app.asterisk.ami.host:asterisk}")
    private String amiHost;

    @Value("${app.asterisk.ami.port:5038}")
    private int amiPort;

    @Value("${app.asterisk.ami.user:asteriskia}")
    private String amiUser;

    @Value("${app.asterisk.ami.password:asteriskia_ami_pass}")
    private String amiPassword;

    private static final int AMI_TIMEOUT_MS = 8_000;

    /** Nomes das seções relacionadas ao tronco principal (lidas e reescritas juntas). */
    private static final List<String> TRUNK_SECTIONS = List.of(
            "tronco-sip-auth", "tronco-sip-aor", "tronco-sip", "tronco-sip-reg"
    );

    // ── Mapeamento campos pjsip → variáveis .env ──────────────────────────────
    // Apenas os campos presentes no bloco do usuário são usados para atualizar o .env.
    // Campos não reconhecidos são preservados no bloco mas ignorados no .env.
    private static final Map<String, String> FIELD_TO_ENV = Map.of(
            "host",        "SIP_TRUNK_HOST",
            "username",    "SIP_TRUNK_USER",
            "password",    "SIP_TRUNK_PASSWORD",
            "from_domain", "SIP_TRUNK_FROM_DOMAIN"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // GET — retorna o bloco atual do template
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Retorna o bloco [tronco-sip] atual do pjsip.conf.template")
    public ResponseEntity<?> getCurrentBlock() {
        try {
            String template = readTemplate();
            String block = extractTrunkBlock(template);
            return ResponseEntity.ok(Map.of("block", block));
        } catch (IOException e) {
            log.error("Erro ao ler template pjsip: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao ler pjsip.conf.template: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST — salva bloco + atualiza .env + reload pjsip
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Salva o bloco [tronco-sip] e recarrega o PJSIP no Asterisk")
    public ResponseEntity<?> saveSipBlock(
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {

        String rawBlock = body.get("block");
        if (rawBlock == null || rawBlock.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Campo 'block' não pode ser vazio."));

        String normalizedBlock = rawBlock.strip();

        try {
            // 1. Reescreve o pjsip.conf.template substituindo apenas as seções do tronco
            String template    = readTemplate();
            String newTemplate = replaceTrunkSections(template, normalizedBlock);
            writeTemplate(newTemplate);
            log.info("pjsip.conf.template atualizado — seção [tronco-sip] substituída");

            // 2. Extrai campos para atualizar o .env
            Map<String, String> envUpdates = extractEnvUpdates(normalizedBlock);
            if (!envUpdates.isEmpty()) {
                String changedBy = auth != null ? auth.getName() : "admin";
                String ip        = extractIp(request);
                settingsService.writeSettings(envUpdates, changedBy, ip);
                log.info("Variáveis SIP atualizadas no .env: {}", envUpdates.keySet());
            }

            // 3. Reload pjsip via AMI (best-effort — não falha o request se o Asterisk estiver fora)
            String reloadResult = reloadPjsip();

            auditService.log(request, "SIP_RAW_SAVE",
                    "Bloco [tronco-sip] atualizado manualmente. Reload: " + reloadResult, true);

            return ResponseEntity.ok(Map.of(
                    "message",      "Configuração SIP salva com sucesso.",
                    "reloadStatus", reloadResult,
                    "envKeys",      envUpdates.keySet()
            ));

        } catch (IOException e) {
            log.error("Erro ao salvar bloco SIP: {}", e.getMessage(), e);
            auditService.log(request, "SIP_RAW_SAVE", "Falha: " + e.getMessage(), false);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao salvar: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Privado — template I/O
    // ─────────────────────────────────────────────────────────────────────────

    private String readTemplate() throws IOException {
        File f = new File(asteriskConfigDir, "pjsip.conf.template");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        }
    }

    private void writeTemplate(String content) throws IOException {
        File f = new File(asteriskConfigDir, "pjsip.conf.template");
        File tmp = new File(asteriskConfigDir, "pjsip.conf.template.tmp");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
        if (!tmp.renameTo(f)) {
            // fallback se rename falhar (sistemas de arquivos distintos)
            try (InputStream in = new FileInputStream(tmp);
                 OutputStream out = new FileOutputStream(f)) {
                in.transferTo(out);
            }
            tmp.delete();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Privado — parse / replace do bloco no template
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extrai do template todas as seções do tronco (tronco-sip-auth, tronco-sip-aor,
     * tronco-sip, tronco-sip-reg) como um único bloco de texto.
     */
    private String extractTrunkBlock(String template) {
        // Identifica a primeira seção do tronco e captura até a seção seguinte fora do grupo
        Pattern p = Pattern.compile(
                "(?m)^\\[tronco-sip(?:-auth|-aor|-reg)?\\].*?(?=^\\[[^]]+\\]|\\z)",
                Pattern.DOTALL | Pattern.MULTILINE);
        StringBuilder sb = new StringBuilder();
        Matcher m = p.matcher(template);
        while (m.find()) sb.append(m.group().stripTrailing()).append("\n\n");
        return sb.toString().stripTrailing();
    }

    /**
     * Remove todas as seções do tronco do template e insere o novo bloco
     * no lugar da primeira ocorrência, preservando todo o restante.
     */
    private String replaceTrunkSections(String template, String newBlock) {
        // Localiza todas as seções do tronco e marca a posição da primeira
        Pattern secPattern = Pattern.compile(
                "(?m)(^\\[tronco-sip(?:-auth|-aor|-reg)?\\][^\\[]*)",
                Pattern.DOTALL);
        Matcher m = secPattern.matcher(template);

        int firstStart = -1;
        List<int[]> ranges = new ArrayList<>();
        while (m.find()) {
            ranges.add(new int[]{m.start(), m.end()});
            if (firstStart < 0) firstStart = m.start();
        }

        if (ranges.isEmpty()) {
            // Nenhuma seção encontrada → appenda ao final
            return template.stripTrailing() + "\n\n" + newBlock + "\n";
        }

        // Remove todas as seções (de trás para frente para não deslocar índices)
        StringBuilder sb = new StringBuilder(template);
        for (int i = ranges.size() - 1; i >= 0; i--) {
            sb.delete(ranges.get(i)[0], ranges.get(i)[1]);
        }

        // Calcula posição de inserção (ajustada pelas deleções anteriores à firstStart)
        int offset = 0;
        for (int[] r : ranges) {
            if (r[0] < firstStart) offset += (r[1] - r[0]);
        }
        int insertAt = firstStart - offset;

        sb.insert(insertAt, newBlock + "\n\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Privado — extrai variáveis .env do bloco livre
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lê linhas do bloco e mapeia campos conhecidos para variáveis de ambiente.
     * Ignora cabeçalhos de seção ([...]) e comentários (;...).
     */
    private Map<String, String> extractEnvUpdates(String block) {
        Map<String, String> updates = new LinkedHashMap<>();
        for (String line : block.lines().toList()) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith(";") || t.startsWith("[")) continue;
            int eq = t.indexOf('=');
            if (eq < 1) continue;
            String field = t.substring(0, eq).strip().toLowerCase();
            String value = t.substring(eq + 1).strip();
            String envKey = FIELD_TO_ENV.get(field);
            if (envKey != null && !value.isBlank()) {
                updates.put(envKey, value);
            }
        }
        return updates;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Privado — reload PJSIP via AMI
    // ─────────────────────────────────────────────────────────────────────────

    private String reloadPjsip() {
        try (Socket socket = new Socket(amiHost, amiPort)) {
            socket.setSoTimeout(AMI_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            reader.readLine(); // banner

            // Login
            sendAmiBlock(writer, Map.of("Action","Login","Username",amiUser,"Secret",amiPassword));
            String login = readAmiBlock(reader);
            if (!login.contains("Success")) {
                log.warn("AMI: falha na autenticação para reload pjsip");
                return "ami_auth_failed";
            }

            // Reload
            sendAmiBlock(writer, Map.of("Action","Command","Command","module reload res_pjsip"));
            String resp = readAmiBlock(reader);
            log.info("AMI pjsip reload: {}", resp.trim());

            sendAmiBlock(writer, Map.of("Action","Logoff"));
            return resp.contains("Success") || resp.contains("Output") ? "ok" : "warn:" + resp.trim();

        } catch (SocketTimeoutException e) {
            log.warn("AMI: timeout ao tentar reload pjsip");
            return "ami_timeout";
        } catch (IOException e) {
            log.warn("AMI: erro ao reload pjsip — {}", e.getMessage());
            return "ami_error";
        }
    }

    private void sendAmiBlock(PrintWriter writer, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n");
        writer.print(sb);
        writer.flush();
    }

    private String readAmiBlock(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) break;
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }
}
