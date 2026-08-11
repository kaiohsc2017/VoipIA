package com.asteriskia.domain.settings;

import com.asteriskia.integration.ami.AmiSession;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AsteriskConfigService — lógica de edição dos arquivos de configuração do Asterisk (I/O de
 * arquivo, parsing de seção pjsip, reload via AMI), extraída de {@link AsteriskConfigController}
 * (fase 20, O3.2 da refatoração) seguindo o mesmo padrão já usado em {@code AsteriskAclService}
 * (extraído do antigo {@code SecurityController}): o service não recebe {@code HttpServletRequest}
 * nem {@code Authentication} — essas dependências de request ficam no controller, que é quem decide
 * auditoria e atualização de configurações vindas do request.
 */
@Slf4j
@Service
public class AsteriskConfigService {

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
    private static final Map<String, String> PJSIP_TO_ENV =
            Map.of(
                    "host", "SIP_TRUNK_HOST",
                    "username", "SIP_TRUNK_USER",
                    "password", "SIP_TRUNK_PASSWORD",
                    "from_domain", "SIP_TRUNK_FROM_DOMAIN");

    // =========================================================================
    // TRONCO SIP — pjsip.conf.template
    // =========================================================================

    public String readTroncoBlock() throws IOException {
        String template = readFile("pjsip.conf.template");
        return extractSection(template, "tronco-sip");
    }

    /** Substitui a seção [tronco-sip] no template e devolve os campos .env mapeados. */
    public Map<String, String> saveTronco(String normalizedBlock) throws IOException {
        String template = readFile("pjsip.conf.template");
        String newTemplate = replaceSection(template, "tronco-sip", normalizedBlock);
        writeFile("pjsip.conf.template", newTemplate);
        return extractEnvFromPjsip(normalizedBlock);
    }

    public String reloadPjsip() {
        return amiReload("module reload res_pjsip");
    }

    // =========================================================================
    // ROTAS — extensions.conf
    // =========================================================================

    public String readRotas() throws IOException {
        return readFile("extensions.conf");
    }

    public void saveRotas(String content) throws IOException {
        writeFile("extensions.conf", content);
    }

    public String reloadDialplan() {
        return amiReload("dialplan reload");
    }

    // =========================================================================
    // I/O de arquivos
    // =========================================================================

    private String readFile(String filename) throws IOException {
        Path path = Path.of(configDir, filename);
        if (!Files.exists(path)) throw new IOException("Arquivo não encontrado: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private void writeFile(String filename, String content) throws IOException {
        Path path = Path.of(configDir, filename);
        Path tmp = Path.of(configDir, filename + ".tmp");
        Files.writeString(
                tmp,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Arquivo {} atualizado em {}", filename, configDir);
    }

    // =========================================================================
    // Parse da seção pjsip
    // =========================================================================

    /**
     * Extrai do template a seção [sectionName] — captura do cabeçalho até o próximo cabeçalho de
     * seção ou fim de arquivo.
     */
    private String extractSection(String content, String sectionName) {
        // Faz match da seção exata (ex: [tronco-sip]) e para antes da próxima seção
        Pattern p =
                Pattern.compile(
                        "(?m)^\\[" + Pattern.quote(sectionName) + "\\][^\\[]*", Pattern.DOTALL);
        Matcher m = p.matcher(content);
        return m.find() ? m.group().stripTrailing() : "";
    }

    /**
     * Substitui a seção [tronco-sip] no template pelo novo bloco. Se a seção não existir, apenda ao
     * final.
     */
    private String replaceSection(String template, String sectionName, String newBlock) {
        Pattern p =
                Pattern.compile(
                        "(?m)^\\[" + Pattern.quote(sectionName) + "\\][^\\[]*", Pattern.DOTALL);
        Matcher m = p.matcher(template);
        if (m.find()) {
            return template.substring(0, m.start())
                    + newBlock
                    + "\n\n"
                    + template.substring(m.end()).stripLeading();
        }
        return template.stripTrailing() + "\n\n" + newBlock + "\n";
    }

    /** Lê linhas do bloco pjsip e extrai campos mapeados para variáveis .env. */
    private Map<String, String> extractEnvFromPjsip(String block) {
        Map<String, String> updates = new LinkedHashMap<>();
        for (String line : block.lines().toList()) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith(";") || t.startsWith("[")) continue;
            int eq = t.indexOf('=');
            if (eq < 1) continue;
            String field = t.substring(0, eq).strip().toLowerCase();
            String value = t.substring(eq + 1).strip();
            String envKey = PJSIP_TO_ENV.get(field);
            if (envKey != null && !value.isBlank()) updates.put(envKey, value);
        }
        return updates;
    }

    // =========================================================================
    // AMI
    // =========================================================================

    private String amiReload(String command) {
        try (AmiSession ami = AmiSession.connect(amiHost, amiPort, AMI_TIMEOUT_MS)) {
            if (!ami.login(amiUser, amiPassword)) {
                log.warn("AMI: falha autenticação para reload");
                return "ami_auth_failed";
            }

            ami.send(Map.of("Action", "Command", "Command", command));
            String resp = ami.readBlock();
            log.info("AMI [{}]: {}", command, resp.trim());

            ami.logoff();
            return resp.contains("Success") || resp.contains("Output")
                    ? "ok"
                    : "warn:" + resp.trim();

        } catch (SocketTimeoutException e) {
            log.warn("AMI: timeout — {}", command);
            return "ami_timeout";
        } catch (IOException e) {
            log.warn("AMI: erro — {}: {}", command, e.getMessage());
            return "ami_error";
        }
    }
}
