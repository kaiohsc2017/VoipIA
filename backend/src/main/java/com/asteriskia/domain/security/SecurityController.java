package com.asteriskia.domain.security;

import com.asteriskia.domain.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.*;

/**
 * SecurityController — Gestão de segurança SIP via fail2ban + ACL Asterisk.
 *
 * fail2ban (via socket /var/run/fail2ban/fail2ban.sock):
 *   GET  /api/v1/security/status          → status geral + estatísticas
 *   GET  /api/v1/security/jails           → lista jails com contagem de bans
 *   GET  /api/v1/security/jails/{jail}    → detalhe de um jail
 *   PUT  /api/v1/security/jails/{jail}    → atualiza configuração do jail
 *   POST /api/v1/security/jails/{jail}/enable|disable
 *
 * IPs bloqueados:
 *   GET  /api/v1/security/banned          → todos os IPs banidos
 *   POST /api/v1/security/ban             → bloquear IP manualmente
 *   DELETE /api/v1/security/ban/{ip}      → desbloquear IP
 *
 * Lista branca:
 *   GET  /api/v1/security/whitelist       → IPs na lista branca
 *   POST /api/v1/security/whitelist       → adicionar IP
 *   DELETE /api/v1/security/whitelist/{ip}
 *
 * Monitoramento:
 *   GET  /api/v1/security/threats         → IPs em monitoramento (próximos do limite)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
@Tag(name = "Security", description = "Proteção SIP via fail2ban + ACL Asterisk")
public class SecurityController {

    private final AuditService auditService;

    @Value("${app.security.fail2ban-socket:/var/run/fail2ban/fail2ban.sock}")
    private String fail2banSocket;

    @Value("${app.security.jail-config-dir:/opt/AsteriskIA/security/config/jail.d}")
    private String jailConfigDir;

    @Value("${app.security.filter-config-dir:/opt/AsteriskIA/security/config/filter.d}")
    private String filterConfigDir;

    @Value("${app.asterisk.config-dir:/etc/asterisk}")
    private String asteriskConfigDir;

    private static final List<String> MANAGED_JAILS =
        List.of("asterisk-auth", "asterisk-scan", "asterisk-flood");

    // =========================================================================
    // STATUS GERAL
    // =========================================================================

    @GetMapping("/status")
    @Operation(summary = "Status geral do sistema de segurança")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean f2bRunning = isF2bRunning();
        result.put("fail2banRunning", f2bRunning);
        result.put("fail2banSocket",  fail2banSocket);

        int totalBanned  = 0;
        int activeJails  = 0;
        List<Map<String,Object>> jails = new ArrayList<>();

        for (String jail : MANAGED_JAILS) {
            Map<String,Object> jailInfo = getJailInfo(jail, f2bRunning);
            jails.add(jailInfo);
            if (Boolean.TRUE.equals(jailInfo.get("enabled"))) activeJails++;
            Object banned = jailInfo.get("currentlyBanned");
            if (banned instanceof Integer) totalBanned += (Integer) banned;
        }

        result.put("jails",       jails);
        result.put("activeJails", activeJails);
        result.put("totalBanned", totalBanned);
        result.put("whitelist",   readWhitelist());
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // JAILS
    // =========================================================================

    @GetMapping("/jails")
    @Operation(summary = "Lista todos os jails gerenciados")
    public ResponseEntity<List<Map<String,Object>>> jails() {
        boolean f2b = isF2bRunning();
        return ResponseEntity.ok(
            MANAGED_JAILS.stream().map(j -> getJailInfo(j, f2b)).collect(Collectors.toList()));
    }

    @GetMapping("/jails/{jail}")
    @Operation(summary = "Detalhe de um jail — configuração atual + regex do filtro")
    public ResponseEntity<Map<String, Object>> jailDetail(@PathVariable String jail) {
        if (!MANAGED_JAILS.contains(jail))
            return ResponseEntity.badRequest().body(Map.of("message", "Jail desconhecido: " + jail));
        Map<String,Object> info = getJailInfo(jail, isF2bRunning());
        // Adiciona regex do filtro
        info.put("filterRegex", readFilterRegex(jail));
        info.put("jailConfig",  readJailConfig(jail));
        return ResponseEntity.ok(info);
    }

    @PutMapping("/jails/{jail}")
    @Operation(summary = "Atualiza configuração de um jail e recarrega o fail2ban")
    public ResponseEntity<Map<String, Object>> updateJail(
            @PathVariable String jail,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        if (!MANAGED_JAILS.contains(jail))
            return ResponseEntity.badRequest().body(Map.of("message", "Jail desconhecido: " + jail));
        try {
            // Atualiza jail.d/asterisk.conf
            updateJailParam(jail, "maxretry", String.valueOf(body.get("maxretry")));
            updateJailParam(jail, "findtime",  String.valueOf(body.get("findtime")));
            updateJailParam(jail, "bantime",   String.valueOf(body.get("bantime")));
            if (body.containsKey("action"))
                updateJailParam(jail, "banaction", String.valueOf(body.get("action")));

            // Atualiza regex do filtro se fornecido
            if (body.containsKey("filterRegex")) {
                writeFilterRegex(jail, String.valueOf(body.get("filterRegex")));
            }

            // Recarrega fail2ban
            String reload = f2bClient("reload", jail);
            auditService.log(request, "SECURITY_JAIL_UPDATE",
                "Jail " + jail + " atualizado. Reload: " + reload, true);

            return ResponseEntity.ok(Map.of(
                "message", "Jail atualizado com sucesso.",
                "reload",  reload));
        } catch (Exception e) {
            log.error("Erro ao atualizar jail {}: {}", jail, e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro: " + e.getMessage()));
        }
    }

    @PostMapping("/jails/{jail}/enable")
    public ResponseEntity<Map<String,Object>> enableJail(
            @PathVariable String jail, HttpServletRequest request) {
        return toggleJail(jail, true, request);
    }

    @PostMapping("/jails/{jail}/disable")
    public ResponseEntity<Map<String,Object>> disableJail(
            @PathVariable String jail, HttpServletRequest request) {
        return toggleJail(jail, false, request);
    }

    // =========================================================================
    // BAN / UNBAN
    // =========================================================================

    @GetMapping("/banned")
    @Operation(summary = "Lista todos os IPs banidos em todos os jails")
    public ResponseEntity<List<Map<String,String>>> banned() {
        List<Map<String,String>> all = new ArrayList<>();
        for (String jail : MANAGED_JAILS) {
            String out = f2bClient("status", jail);
            List<String> ips = parseBannedIps(out);
            for (String ip : ips) {
                Map<String,String> entry = new LinkedHashMap<>();
                entry.put("ip",     ip);
                entry.put("jail",   jail);
                entry.put("origin", "fail2ban");
                all.add(entry);
            }
        }
        // Adiciona bloqueios manuais (ACL Asterisk)
        all.addAll(readManualBans());
        return ResponseEntity.ok(all);
    }

    @PostMapping("/ban")
    @Operation(summary = "Bloquear IP manualmente — via fail2ban + ACL Asterisk")
    public ResponseEntity<Map<String, Object>> ban(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        String ip   = body.get("ip");
        String note = body.getOrDefault("note", "Bloqueio manual");
        String jail = body.getOrDefault("jail", "asterisk-auth");

        if (ip == null || ip.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "IP obrigatório"));
        if (!isValidIp(ip))
            return ResponseEntity.badRequest().body(Map.of("message", "IP ou CIDR inválido: " + ip));

        try {
            // Ban via fail2ban
            String f2bResult = f2bClient("set", jail, "banip", ip);
            // Ban via ACL Asterisk (acl.conf)
            addToAsteriskAcl(ip);
            // Salva nota
            saveManualBan(ip, note, jail);

            auditService.log(request, "SECURITY_BAN",
                "IP banido: " + ip + " | Jail: " + jail + " | " + note, true);

            return ResponseEntity.ok(Map.of(
                "message",    "IP " + ip + " bloqueado com sucesso.",
                "fail2ban",   f2bResult,
                "asteriskAcl","ok"));
        } catch (Exception e) {
            log.error("Erro ao banir IP {}: {}", ip, e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro ao banir: " + e.getMessage()));
        }
    }

    @DeleteMapping("/ban/{ip}")
    @Operation(summary = "Desbloquear IP — remove de todos os jails e da ACL")
    public ResponseEntity<Map<String, Object>> unban(
            @PathVariable String ip,
            @RequestParam(defaultValue = "") String jail,
            HttpServletRequest request) {
        try {
            List<String> results = new ArrayList<>();
            List<String> jailsToUnban = jail.isBlank() ? MANAGED_JAILS : List.of(jail);
            for (String j : jailsToUnban)
                results.add(j + ": " + f2bClient("set", j, "unbanip", ip));

            removeFromAsteriskAcl(ip);
            removeManualBan(ip);

            auditService.log(request, "SECURITY_UNBAN", "IP desbloqueado: " + ip, true);
            return ResponseEntity.ok(Map.of(
                "message", "IP " + ip + " desbloqueado.",
                "results", results));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro ao desbanir: " + e.getMessage()));
        }
    }

    // =========================================================================
    // WHITELIST
    // =========================================================================

    @GetMapping("/whitelist")
    public ResponseEntity<List<String>> whitelist() {
        return ResponseEntity.ok(readWhitelist());
    }

    @PostMapping("/whitelist")
    public ResponseEntity<Map<String, Object>> addWhitelist(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String ip = body.get("ip");
        if (!isValidIp(ip))
            return ResponseEntity.badRequest().body(Map.of("message", "IP inválido: " + ip));
        try {
            List<String> list = readWhitelist();
            if (!list.contains(ip)) {
                list.add(ip);
                writeWhitelist(list);
                // Atualiza ignoreip em todos os jails
                updateIgnoreIp(list);
                f2bClient("reload");
            }
            auditService.log(request, "SECURITY_WHITELIST_ADD", "IP adicionado à lista branca: " + ip, true);
            return ResponseEntity.ok(Map.of("message", ip + " adicionado à lista branca."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro: " + e.getMessage()));
        }
    }

    @DeleteMapping("/whitelist/{ip}")
    public ResponseEntity<Map<String, Object>> removeWhitelist(
            @PathVariable String ip,
            HttpServletRequest request) {
        try {
            List<String> list = readWhitelist();
            list.remove(ip);
            writeWhitelist(list);
            updateIgnoreIp(list);
            f2bClient("reload");
            auditService.log(request, "SECURITY_WHITELIST_REMOVE", "IP removido da lista branca: " + ip, true);
            return ResponseEntity.ok(Map.of("message", ip + " removido da lista branca."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro: " + e.getMessage()));
        }
    }

    // =========================================================================
    // THREATS — IPs em monitoramento
    // =========================================================================

    @GetMapping("/threats")
    @Operation(summary = "IPs em monitoramento — próximos do limite de ban")
    public ResponseEntity<List<Map<String,Object>>> threats() {
        // Lê fail2ban-client get <jail> monitoredip (disponível no fail2ban ≥ 0.11)
        List<Map<String,Object>> result = new ArrayList<>();
        for (String jail : MANAGED_JAILS) {
            try {
                String out = f2bClient("get", jail, "monitored");
                if (out == null || out.isBlank()) continue;
                // Parseia saída: "IP = <ip>\tFailures = <n>\tTotal = <n>"
                for (String line : out.split("\n")) {
                    line = line.trim();
                    if (line.isBlank() || line.startsWith("No")) continue;
                    Map<String,Object> entry = parseMonitoredLine(line, jail);
                    if (entry != null) result.add(entry);
                }
            } catch (Exception e) {
                log.debug("Threats: jail {} — {}", jail, e.getMessage());
            }
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/test-regex")
    @Operation(summary = "Testa uma regex contra as últimas linhas do log do Asterisk")
    public ResponseEntity<Map<String, Object>> testRegex(
            @RequestBody Map<String, String> body) {
        String regex = body.get("regex");
        int lines = Integer.parseInt(body.getOrDefault("lines", "200"));
        if (regex == null || regex.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Regex obrigatória."));
        try {
            List<String> logLines = tailAsteriskLog(lines);
            Pattern p = Pattern.compile(regex);
            List<String> matches = logLines.stream()
                .filter(l -> p.matcher(l).find())
                .limit(20)
                .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of(
                "matches", matches,
                "count",   matches.size(),
                "tested",  logLines.size()));
        } catch (PatternSyntaxException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Regex inválida: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    // =========================================================================
    // Privado — fail2ban-client
    // =========================================================================

    private boolean isF2bRunning() {
        try {
            String out = f2bClient("ping");
            return out != null && out.contains("pong");
        } catch (Exception e) { return false; }
    }

    private String f2bClient(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("fail2ban-client");
            cmd.add("--socket");
            cmd.add(fail2banSocket);
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining("\n"));
            }
            p.waitFor(10, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            log.warn("fail2ban-client {}: {}", String.join(" ", args), e.getMessage());
            return "";
        }
    }

    private Map<String,Object> getJailInfo(String jail, boolean f2bRunning) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("name", jail);

        // Lê configuração do arquivo
        Map<String,String> cfg = parseJailConfig(jail);
        m.put("enabled",   "true".equals(cfg.getOrDefault("enabled","false")));
        m.put("maxretry",  parseInt(cfg.getOrDefault("maxretry","5")));
        m.put("findtime",  parseInt(cfg.getOrDefault("findtime","30")));
        m.put("bantime",   parseInt(cfg.getOrDefault("bantime","86400")));
        m.put("banaction", cfg.getOrDefault("banaction","iptables-multiport"));
        m.put("port",      cfg.getOrDefault("port","5060,5061,8088"));

        // Status live do fail2ban
        if (f2bRunning) {
            try {
                String status = f2bClient("status", jail);
                m.put("currentlyBanned", parseBannedCount(status));
                m.put("totalFailed",     parseTotalFailed(status));
            } catch (Exception e) {
                m.put("currentlyBanned", 0);
                m.put("totalFailed",     0);
            }
        } else {
            m.put("currentlyBanned", 0);
            m.put("totalFailed",     0);
        }
        return m;
    }

    private ResponseEntity<Map<String,Object>> toggleJail(
            String jail, boolean enable, HttpServletRequest request) {
        if (!MANAGED_JAILS.contains(jail))
            return ResponseEntity.badRequest().body(Map.of("message","Jail desconhecido: " + jail));
        try {
            updateJailParam(jail, "enabled", enable ? "true" : "false");
            String reload = f2bClient("reload", jail);
            auditService.log(request, "SECURITY_JAIL_TOGGLE",
                jail + " " + (enable ? "habilitado" : "desabilitado"), true);
            return ResponseEntity.ok(Map.of(
                "message", jail + (enable ? " habilitado." : " desabilitado."),
                "reload",  reload));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("message","Erro: " + e.getMessage()));
        }
    }

    // =========================================================================
    // Privado — config files
    // =========================================================================

    private Map<String,String> parseJailConfig(String jail) {
        Map<String,String> cfg = new LinkedHashMap<>();
        try {
            Path path = Path.of(jailConfigDir, "asterisk.conf");
            String content = Files.readString(path, StandardCharsets.UTF_8);
            // Encontra a seção do jail
            Pattern sec = Pattern.compile(
                "\\[" + Pattern.quote(jail) + "\\]([^\\[]*)", Pattern.DOTALL);
            Matcher m = sec.matcher(content);
            if (m.find()) {
                for (String line : m.group(1).split("\n")) {
                    line = line.trim();
                    if (line.isBlank() || line.startsWith(";") || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) cfg.put(line.substring(0,eq).trim(), line.substring(eq+1).trim());
                }
            }
        } catch (Exception e) { log.warn("parseJailConfig {}: {}", jail, e.getMessage()); }
        return cfg;
    }

    private void updateJailParam(String jail, String key, String value) throws IOException {
        Path path = Path.of(jailConfigDir, "asterisk.conf");
        String content = Files.readString(path, StandardCharsets.UTF_8);
        Pattern sec = Pattern.compile(
            "(\\[" + Pattern.quote(jail) + "\\][^\\[]*)(?=\\[|\\z)", Pattern.DOTALL);
        Matcher m = sec.matcher(content);
        if (!m.find()) throw new IOException("Jail " + jail + " não encontrado no arquivo de config");
        String section = m.group(1);
        String updated;
        Pattern kp = Pattern.compile("(?m)^(\\s*" + Pattern.quote(key) + "\\s*=\\s*).*$");
        Matcher km = kp.matcher(section);
        if (km.find()) {
            updated = section.substring(0, km.start()) + key + "  = " + value + section.substring(km.end());
        } else {
            updated = section.stripTrailing() + "\n" + key + "  = " + value + "\n";
        }
        content = m.replaceFirst(Matcher.quoteReplacement(updated));
        Files.writeString(path, content, StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
    }

    private String readJailConfig(String jail) {
        try {
            Path path = Path.of(jailConfigDir, "asterisk.conf");
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Pattern sec = Pattern.compile(
                "\\[" + Pattern.quote(jail) + "\\][^\\[]*", Pattern.DOTALL);
            Matcher m = sec.matcher(content);
            return m.find() ? m.group().strip() : "";
        } catch (Exception e) { return ""; }
    }

    private String readFilterRegex(String jail) {
        try {
            Path path = Path.of(filterConfigDir, jail + ".conf");
            if (!Files.exists(path)) return "";
            String content = Files.readString(path, StandardCharsets.UTF_8);
            // Extrai linhas de failregex
            StringBuilder sb = new StringBuilder();
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (t.startsWith("failregex") || (sb.length()>0 && t.startsWith("^")))
                    sb.append(t).append("\n");
            }
            return sb.toString().strip();
        } catch (Exception e) { return ""; }
    }

    private void writeFilterRegex(String jail, String regex) throws IOException {
        Path path = Path.of(filterConfigDir, jail + ".conf");
        String content = Files.readString(path, StandardCharsets.UTF_8);
        // Substitui bloco failregex
        Pattern p = Pattern.compile("(?m)^failregex\\s*=.*?(?=^[a-z]|\\z)", Pattern.DOTALL);
        String newBlock = "failregex = " + regex.strip().replace("\n", "\n            ") + "\n\n";
        String updated = p.matcher(content).replaceFirst(Matcher.quoteReplacement(newBlock));
        Files.writeString(path, updated, StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
    }

    // =========================================================================
    // Privado — ACL Asterisk
    // =========================================================================

    private void addToAsteriskAcl(String ip) throws IOException {
        Path aclPath = Path.of(asteriskConfigDir, "acl.conf");
        String content = Files.exists(aclPath)
            ? Files.readString(aclPath, StandardCharsets.UTF_8) : "[blacklist]\ntype=acl\n";
        if (!content.contains(ip)) {
            content = content.stripTrailing() + "\ndeny=" + ip + "\n";
            Files.writeString(aclPath, content, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        }
    }

    private void removeFromAsteriskAcl(String ip) throws IOException {
        Path aclPath = Path.of(asteriskConfigDir, "acl.conf");
        if (!Files.exists(aclPath)) return;
        String content = Files.readString(aclPath, StandardCharsets.UTF_8);
        content = content.lines()
            .filter(l -> !l.contains("deny=" + ip))
            .collect(Collectors.joining("\n")) + "\n";
        Files.writeString(aclPath, content, StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
    }

    // =========================================================================
    // Privado — manual bans persistence
    // =========================================================================

    private static final String MANUAL_BANS_FILE = "/opt/AsteriskIA/security/manual-bans.csv";

    private List<Map<String,String>> readManualBans() {
        List<Map<String,String>> list = new ArrayList<>();
        try {
            Path p = Path.of(MANUAL_BANS_FILE);
            if (!Files.exists(p)) return list;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(",", 4);
                if (parts.length >= 2) {
                    Map<String,String> m = new LinkedHashMap<>();
                    m.put("ip",     parts[0].trim());
                    m.put("jail",   parts.length>1 ? parts[1].trim() : "manual");
                    m.put("note",   parts.length>2 ? parts[2].trim() : "");
                    m.put("ts",     parts.length>3 ? parts[3].trim() : "");
                    m.put("origin", "manual");
                    list.add(m);
                }
            }
        } catch (Exception e) { log.warn("readManualBans: {}", e.getMessage()); }
        return list;
    }

    private void saveManualBan(String ip, String note, String jail) {
        try {
            Path p = Path.of(MANUAL_BANS_FILE);
            Files.createDirectories(p.getParent());
            String line = ip + "," + jail + "," + note.replace(",","；") + "," + Instant.now() + "\n";
            Files.writeString(p, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { log.warn("saveManualBan: {}", e.getMessage()); }
    }

    private void removeManualBan(String ip) {
        try {
            Path p = Path.of(MANUAL_BANS_FILE);
            if (!Files.exists(p)) return;
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8)
                .stream().filter(l -> !l.startsWith(ip + ",")).collect(Collectors.toList());
            Files.write(p, lines, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } catch (Exception e) { log.warn("removeManualBan: {}", e.getMessage()); }
    }

    // =========================================================================
    // Privado — whitelist
    // =========================================================================

    private static final String WHITELIST_FILE = "/opt/AsteriskIA/security/whitelist.txt";
    private static final List<String> DEFAULT_WHITELIST =
        List.of("127.0.0.1/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    private List<String> readWhitelist() {
        try {
            Path p = Path.of(WHITELIST_FILE);
            if (!Files.exists(p)) return new ArrayList<>(DEFAULT_WHITELIST);
            return Files.readAllLines(p, StandardCharsets.UTF_8)
                .stream().filter(l -> !l.isBlank() && !l.startsWith("#"))
                .collect(Collectors.toList());
        } catch (Exception e) { return new ArrayList<>(DEFAULT_WHITELIST); }
    }

    private void writeWhitelist(List<String> ips) throws IOException {
        Path p = Path.of(WHITELIST_FILE);
        Files.createDirectories(p.getParent());
        String content = "# AsteriskIA — Lista branca de IPs\n# IPs aqui nunca serão bloqueados pelo fail2ban\n"
            + String.join("\n", ips) + "\n";
        Files.writeString(p, content, StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
    }

    private void updateIgnoreIp(List<String> whitelist) throws IOException {
        String ignoreIp = String.join(" ", whitelist);
        updateJailParam("asterisk-auth", "ignoreip", ignoreIp);
        updateJailParam("asterisk-scan", "ignoreip", ignoreIp);
        updateJailParam("asterisk-flood","ignoreip", ignoreIp);
    }

    // =========================================================================
    // Privado — parse helpers
    // =========================================================================

    private List<String> parseBannedIps(String f2bStatus) {
        List<String> ips = new ArrayList<>();
        if (f2bStatus == null || f2bStatus.isBlank()) return ips;
        Pattern p = Pattern.compile("Banned IP list:\\s*(.*)");
        Matcher m = p.matcher(f2bStatus);
        if (m.find()) {
            String raw = m.group(1).trim();
            if (!raw.isEmpty())
                Arrays.stream(raw.split("\\s+")).forEach(ips::add);
        }
        return ips;
    }

    private int parseBannedCount(String status) {
        if (status == null) return 0;
        Pattern p = Pattern.compile("Currently banned:\\s*(\\d+)");
        Matcher m = p.matcher(status);
        return m.find() ? parseInt(m.group(1)) : 0;
    }

    private int parseTotalFailed(String status) {
        if (status == null) return 0;
        Pattern p = Pattern.compile("Total failed:\\s*(\\d+)");
        Matcher m = p.matcher(status);
        return m.find() ? parseInt(m.group(1)) : 0;
    }

    private Map<String,Object> parseMonitoredLine(String line, String jail) {
        // fail2ban-client get <jail> monitored returns:
        // <ip>    failures = <n>    last = <ts>
        Pattern p = Pattern.compile("(\\S+).*failures\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(line);
        if (!m.find()) return null;
        Map<String,Object> e = new LinkedHashMap<>();
        e.put("ip",       m.group(1));
        e.put("failures", parseInt(m.group(2)));
        e.put("jail",     jail);
        return e;
    }

    private List<String> tailAsteriskLog(int lines) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "docker","exec","asteriskia-asterisk",
            "tail","-n",String.valueOf(lines),"/var/log/asterisk/full");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) out.add(l);
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return out;
    }

    private boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        // IP simples ou CIDR
        return ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}(/\\d{1,2})?$")
            || ip.matches("^[0-9a-fA-F:]+(/\\d{1,3})?$");
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}
