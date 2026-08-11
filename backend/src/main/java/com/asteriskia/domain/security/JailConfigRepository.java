package com.asteriskia.domain.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JailConfigRepository — leitura/escrita de asterisk.conf (jail.d) e dos
 * arquivos de failregex (filter.d) usados pelo fail2ban.
 *
 * Extraído de SecurityController (achado de auditoria: arquivo > 800 linhas).
 * Contém, sem mudança de comportamento, o fix de injeção de seção INI em
 * updateJailParam (achado de segurança desta mesma sessão de auditoria).
 */
@Slf4j
@Service
public class JailConfigRepository {

    @Value("${app.security.jail-config-dir:/opt/asteriskia/security/config/jail.d}")
    private String jailConfigDir;

    @Value("${app.security.filter-config-dir:/opt/asteriskia/security/config/filter.d}")
    private String filterConfigDir;

    public Map<String,String> parseJailConfig(String jail) {
        Map<String,String> cfg = new LinkedHashMap<>();
        try {
            String content = Files.readString(
                Path.of(jailConfigDir, "asterisk.conf"), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                "\\[" + Pattern.quote(jail) + "\\]([^\\[]*)", Pattern.DOTALL).matcher(content);
            if (m.find()) {
                for (String line : m.group(1).split("\n")) {
                    line = line.trim();
                    if (line.isBlank() || line.startsWith(";") || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) cfg.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (Exception e) { log.warn("parseJailConfig {}: {}", jail, e.getMessage()); }
        return cfg;
    }

    public void updateJailParam(String jail, String key, String value) throws IOException {
        // Achado de segurança: sem esta checagem, um valor com \r/\n/[/] injeta
        // seções INI arbitrárias em asterisk.conf, recarregado em seguida no
        // container security (NET_ADMIN + network_mode: host). Checagem por
        // contains() em vez de regex ".*[\\r\\n\\[\\]].*" — sem Pattern.DOTALL o "."
        // não cruza quebra de linha, então qualquer valor com 2+ newlines (o
        // mínimo necessário pra injetar uma seção de verdade) passava incólume.
        if (value.contains("\r") || value.contains("\n") || value.contains("[") || value.contains("]"))
            throw new IOException("Valor inválido para " + key + ": não pode conter quebra de linha ou colchetes");

        Path path = Path.of(jailConfigDir, "asterisk.conf");
        String content = Files.readString(path, StandardCharsets.UTF_8);

        // Localiza a seção do jail
        Pattern secPat = Pattern.compile(
            "(\\[" + Pattern.quote(jail) + "\\][^\\[]*)(?=\\[|\\z)", Pattern.DOTALL);
        Matcher secMatcher = secPat.matcher(content);
        if (!secMatcher.find())
            throw new IOException("Seção [" + jail + "] não encontrada em asterisk.conf");

        String section = secMatcher.group(1);
        String updatedSection;

        // Atualiza a linha existente ou adiciona nova
        Pattern keyPat = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*=[ \\t]*).*$");
        Matcher keyMatcher = keyPat.matcher(section);
        if (keyMatcher.find()) {
            updatedSection = keyMatcher.replaceFirst(key + "  = " + Matcher.quoteReplacement(value));
        } else {
            updatedSection = section.stripTrailing() + "\n" + key + "  = " + value + "\n";
        }

        String updated = secMatcher.replaceFirst(Matcher.quoteReplacement(updatedSection));
        SecurityFileUtils.writeAtomic(path, updated);
        log.info("Jail [{}] {} = {}", jail, key, value);
    }

    public String readJailConfig(String jail) {
        try {
            String content = Files.readString(
                Path.of(jailConfigDir, "asterisk.conf"), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                "\\[" + Pattern.quote(jail) + "\\][^\\[]*", Pattern.DOTALL).matcher(content);
            return m.find() ? m.group().strip() : "";
        } catch (Exception e) { return ""; }
    }

    public String readFilterRegex(String jail) {
        try {
            Path path = Path.of(filterConfigDir, jail + ".conf");
            if (!Files.exists(path)) return "";
            String content = Files.readString(path, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            boolean inRegex = false;
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (t.startsWith("failregex")) { sb.append(t).append("\n"); inRegex = true; }
                else if (inRegex && (t.startsWith("^") || t.startsWith(" "))) sb.append(t.trim()).append("\n");
                else if (inRegex && !t.isBlank()) inRegex = false;
            }
            return sb.toString().strip();
        } catch (Exception e) { return ""; }
    }

    public void writeFilterRegex(String jail, String regex) throws IOException {
        Path path = Path.of(filterConfigDir, jail + ".conf");
        if (!Files.exists(path)) return;
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String newBlock = "failregex = " +
            regex.strip().replace("\n", "\n            ") + "\n\n";
        String updated = Pattern.compile("(?m)^failregex\\s*=.*?(?=^[a-z]|\\z)", Pattern.DOTALL)
            .matcher(content).replaceFirst(Matcher.quoteReplacement(newBlock));
        SecurityFileUtils.writeAtomic(path, updated);
    }
}
