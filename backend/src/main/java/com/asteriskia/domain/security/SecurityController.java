package com.asteriskia.domain.security;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class SecurityController {

    private final AuditService auditService;
    private final RestTemplate restTemplate;

    // Docker Helper — único container com acesso ao docker.sock (F-CRIT-10).
    // Este controller não roda mais 'docker exec' via ProcessBuilder.
    @Value("${app.docker-helper.url}")
    private String dockerHelperUrl;

    @Value("${app.internal-api-key}")
    private String internalApiKey;

    // Caminhos montados via volume no backend
    @Value("${app.security.jail-config-dir:/opt/asteriskia/security/config/jail.d}")
    private String jailConfigDir;

    @Value("${app.security.filter-config-dir:/opt/asteriskia/security/config/filter.d}")
    private String filterConfigDir;

    @Value("${app.security.security-dir:/opt/asteriskia/security}")
    private String securityDir;

    @Value("${app.asterisk.config-dir:/etc/asterisk}")
    private String asteriskConfigDir;

    private static final List<String> MANAGED_JAILS =
        List.of("asterisk-auth", "asterisk-scan", "asterisk-flood");

    // ── Status geral ──────────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean f2bRunning = isF2bRunning();
        result.put("fail2banRunning", f2bRunning);

        int totalBanned = 0, activeJails = 0;
        List<Map<String,Object>> jails = new ArrayList<>();
        for (String jail : MANAGED_JAILS) {
            Map<String,Object> info = getJailInfo(jail, f2bRunning);
            jails.add(info);
            if (Boolean.TRUE.equals(info.get("enabled"))) activeJails++;
            Object b = info.get("currentlyBanned");
            if (b instanceof Integer) totalBanned += (Integer) b;
        }
        result.put("jails",       jails);
        result.put("activeJails", activeJails);
        result.put("totalBanned", totalBanned);
        result.put("whitelist",   readWhitelist());
        return ResponseEntity.ok(result);
    }

    // ── Jails ─────────────────────────────────────────────────────────────────

    @GetMapping("/jails")
    public ResponseEntity<List<Map<String,Object>>> jails() {
        boolean f2b = isF2bRunning();
        return ResponseEntity.ok(MANAGED_JAILS.stream()
            .map(j -> getJailInfo(j, f2b)).collect(Collectors.toList()));
    }

    @GetMapping("/jails/{jail}")
    public ResponseEntity<Map<String, Object>> jailDetail(@PathVariable String jail) {
        if (!MANAGED_JAILS.contains(jail))
            return ResponseEntity.badRequest().body(Map.of("message", "Jail desconhecido: " + jail));
        Map<String,Object> info = getJailInfo(jail, isF2bRunning());
        info.put("filterRegex", readFilterRegex(jail));
        info.put("jailConfig",  readJailConfig(jail));
        return ResponseEntity.ok(info);
    }

    @PutMapping("/jails/{jail}")
    public ResponseEntity<Map<String, Object>> updateJail(
            @PathVariable String jail,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        if (!MANAGED_JAILS.contains(jail))
            return ResponseEntity.badRequest().body(Map.of("message", "Jail desconhecido: " + jail));
        try {
            if (body.containsKey("maxretry"))
                updateJailParam(jail, "maxretry", String.valueOf(body.get("maxretry")));
            if (body.containsKey("findtime"))
                updateJailParam(jail, "findtime",  String.valueOf(body.get("findtime")));
            if (body.containsKey("bantime"))
                updateJailParam(jail, "bantime",   String.valueOf(body.get("bantime")));
            if (body.containsKey("banaction"))
                updateJailParam(jail, "banaction", String.valueOf(body.get("banaction")));
            if (body.containsKey("filterRegex"))
                writeFilterRegex(jail, String.valueOf(body.get("filterRegex")));

            String reload = f2bExec("reload", jail);
            auditService.log(request, "SECURITY_JAIL_UPDATE",
                "Jail " + jail + " atualizado. Reload: " + reload, true);
            return ResponseEntity.ok(Map.of("message", "Jail atualizado.", "reload", reload));
        } catch (Exception e) {
            log.error("updateJail {}: {}", jail, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro ao salvar: " + e.getMessage()));
        }
    }

    @PostMapping("/jails/{jail}/enable")
    public ResponseEntity<Map<String,Object>> enableJail(
            @PathVariable String jail, HttpServletRequest req) {
        return toggleJail(jail, true, req);
    }

    @PostMapping("/jails/{jail}/disable")
    public ResponseEntity<Map<String,Object>> disableJail(
            @PathVariable String jail, HttpServletRequest req) {
        return toggleJail(jail, false, req);
    }

    // ── Ban / Unban ───────────────────────────────────────────────────────────

    @GetMapping("/banned")
    public ResponseEntity<List<Map<String,String>>> banned() {
        List<Map<String,String>> all = new ArrayList<>();
        for (String jail : MANAGED_JAILS) {
            for (String ip : parseBannedIps(f2bExec("status", jail))) {
                all.add(mapOf("ip", ip, "jail", jail, "origin", "fail2ban"));
            }
        }
        all.addAll(readManualBans());
        return ResponseEntity.ok(all);
    }

    @PostMapping("/ban")
    public ResponseEntity<Map<String, Object>> ban(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        String ip   = body.get("ip");
        String note = body.getOrDefault("note", "Bloqueio manual");
        String jail = body.getOrDefault("jail", "asterisk-auth");
        if (ip == null || ip.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "IP obrigatório"));
        if (!isValidIp(ip))
            return ResponseEntity.badRequest().body(Map.of("message", "IP inválido: " + ip));
        try {
            String f2bResult = f2bExec("set", jail, "banip", ip);
            addToAsteriskAcl(ip);
            saveManualBan(ip, note, jail);
            auditService.log(request, "SECURITY_BAN", "IP banido: " + ip + " | " + note, true);
            return ResponseEntity.ok(Map.of(
                "message", "IP " + ip + " bloqueado.", "fail2ban", f2bResult));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro ao banir: " + e.getMessage()));
        }
    }

    @DeleteMapping("/ban/{ip}")
    public ResponseEntity<Map<String, Object>> unban(
            @PathVariable String ip,
            @RequestParam(defaultValue = "") String jail,
            HttpServletRequest request) {
        try {
            List<String> jailsToUnban = jail.isBlank() ? MANAGED_JAILS : List.of(jail);
            List<String> results = jailsToUnban.stream()
                .map(j -> j + ": " + f2bExec("set", j, "unbanip", ip))
                .collect(Collectors.toList());
            removeFromAsteriskAcl(ip);
            removeManualBan(ip);
            auditService.log(request, "SECURITY_UNBAN", "IP desbloqueado: " + ip, true);
            return ResponseEntity.ok(Map.of("message", "IP " + ip + " desbloqueado.", "results", results));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro: " + e.getMessage()));
        }
    }

    // ── Whitelist ─────────────────────────────────────────────────────────────

    @GetMapping("/whitelist")
    public ResponseEntity<List<String>> whitelist() {
        return ResponseEntity.ok(readWhitelist());
    }

    @PostMapping("/whitelist")
    public ResponseEntity<Map<String, Object>> addWhitelist(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        String ip = body.get("ip");
        if (!isValidIp(ip))
            return ResponseEntity.badRequest().body(Map.of("message", "IP inválido: " + ip));
        try {
            List<String> list = readWhitelist();
            if (!list.contains(ip)) {
                list.add(ip);
                writeWhitelist(list);
                updateIgnoreIp(list);
                f2bExec("reload");
            }
            reapplyLockdownIfActive(list);
            auditService.log(request, "SECURITY_WHITELIST_ADD", ip, true);
            return ResponseEntity.ok(Map.of("message", ip + " adicionado à lista branca."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro: " + e.getMessage()));
        }
    }

    @DeleteMapping("/whitelist/{ip}")
    public ResponseEntity<Map<String, Object>> removeWhitelist(
            @PathVariable String ip, HttpServletRequest request) {
        try {
            List<String> list = readWhitelist();
            list.remove(ip);
            writeWhitelist(list);
            updateIgnoreIp(list);
            f2bExec("reload");
            reapplyLockdownIfActive(list);
            auditService.log(request, "SECURITY_WHITELIST_REMOVE", ip, true);
            return ResponseEntity.ok(Map.of("message", ip + " removido."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro: " + e.getMessage()));
        }
    }

    // ── Lockdown mode — bloqueia tudo, libera apenas whitelist ──────────────

    @GetMapping("/lockdown")
    public ResponseEntity<Map<String, Object>> lockdownStatus() {
        boolean active = isLockdownActive();
        return ResponseEntity.ok(Map.of(
            "active",      active,
            "description", active
                ? "Modo lockdown ATIVO — apenas IPs da whitelist podem conectar ao SIP"
                : "Modo normal — fail2ban monitora e bloqueia ameaças"
        ));
    }

    @PostMapping("/lockdown/enable")
    public ResponseEntity<Map<String, Object>> enableLockdown(HttpServletRequest request) {
        try {
            List<String> whitelist = readWhitelist();
            applyLockdownIptables(whitelist);
            applyLockdownAcl(whitelist);
            writeLockdownFlag(true);
            auditService.log(request, "SECURITY_LOCKDOWN_ENABLE",
                "Modo lockdown ativado. Whitelist: " + whitelist.size() + " IPs", true);
            return ResponseEntity.ok(Map.of(
                "message", "Lockdown ativado. Apenas IPs da whitelist podem conectar.",
                "whitelistedIps", whitelist.size()
            ));
        } catch (Exception e) {
            log.error("enableLockdown: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro ao ativar lockdown: " + e.getMessage()));
        }
    }

    @PostMapping("/lockdown/disable")
    public ResponseEntity<Map<String, Object>> disableLockdown(HttpServletRequest request) {
        try {
            removeLockdownIptables();
            restorePermissiveAcl();
            writeLockdownFlag(false);
            auditService.log(request, "SECURITY_LOCKDOWN_DISABLE",
                "Modo lockdown desativado. Voltando ao modo fail2ban.", true);
            return ResponseEntity.ok(Map.of(
                "message", "Lockdown desativado. Modo fail2ban ativo."
            ));
        } catch (Exception e) {
            log.error("disableLockdown: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro ao desativar lockdown: " + e.getMessage()));
        }
    }

    /**
     * Quando a whitelist muda, re-aplica as regras se o lockdown estiver ativo.
     * Chamado internamente após addWhitelist / removeWhitelist.
     */
    private void reapplyLockdownIfActive(List<String> whitelist) {
        if (!isLockdownActive()) return;
        try {
            applyLockdownIptables(whitelist);
            applyLockdownAcl(whitelist);
            log.info("Lockdown re-aplicado com {} IPs na whitelist", whitelist.size());
        } catch (Exception e) {
            log.error("reapplyLockdownIfActive: {}", e.getMessage(), e);
        }
    }

    private boolean isLockdownActive() {
        try {
            return Files.exists(Path.of(securityDir, "lockdown.flag"));
        } catch (Exception e) { return false; }
    }

    private void writeLockdownFlag(boolean active) throws IOException {
        Path flag = Path.of(securityDir, "lockdown.flag");
        Files.createDirectories(flag.getParent());
        if (active) Files.writeString(flag, "active", StandardCharsets.UTF_8);
        else        Files.deleteIfExists(flag);
    }

    // Diretório compartilhado com o container security (que tem NET_ADMIN + iptables)
    private static final String SECURITY_CMD_DIR = "/var/run/asteriskia-security";

    /**
     * Aplica lockdown via nft na chain DOCKER-USER.
     * O Docker neste host usa nftables — a chain DOCKER-USER nftables
     * é processada para todo tráfego destinado a containers.
     */
    private void applyLockdownIptables(List<String> whitelist) throws IOException, InterruptedException {
        StringBuilder script = new StringBuilder("#!/bin/bash\n");
        script.append("# Limpa regras anteriores da DOCKER-USER (exceto AMI que pode já existir)\n");
        script.append("nft flush chain ip filter DOCKER-USER 2>/dev/null || true\n");
        script.append("# Whitelist — ACCEPT para IPs autorizados\n");
        for (String ip : whitelist) {
            script.append("nft add rule ip filter DOCKER-USER ip saddr ").append(ip).append(" accept\n");
        }
        script.append("# DROP portas SIP/WebRTC/AMI para todos os outros\n");
        script.append("nft add rule ip filter DOCKER-USER udp dport 5060 drop\n");
        script.append("nft add rule ip filter DOCKER-USER tcp dport 5060 drop\n");
        script.append("nft add rule ip filter DOCKER-USER tcp dport 8088 drop\n");
        script.append("nft add rule ip filter DOCKER-USER tcp dport 5038 drop\n");
        script.append("echo '[lockdown] nft DOCKER-USER aplicado'\n");
        script.append("nft list chain ip filter DOCKER-USER\n");
        writeSecurityCmd("lockdown-enable", script.toString());
        writePersistentLockdown(script.toString());
        log.info("Lockdown nft DOCKER-USER enviado: {} IPs", whitelist.size());
    }

    private void removeLockdownIptables() {
        try {
            String script = "#!/bin/bash\n" +
                "nft flush chain ip filter DOCKER-USER 2>/dev/null || true\n" +
                "echo '[lockdown] DOCKER-USER limpa'\n";
            writeSecurityCmd("lockdown-disable", script);
            removePersistentLockdown();
            log.info("Lockdown disable DOCKER-USER enviado");
        } catch (Exception e) {
            log.warn("removeLockdownIptables: {}", e.getMessage());
        }
    }

    private void writePersistentLockdown(String script) {
        try {
            Path p = Path.of(SECURITY_CMD_DIR, "lockdown-persistent.sh");
            Files.createDirectories(p.getParent());
            Files.writeString(p, script, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) { log.warn("writePersistentLockdown: {}", e.getMessage()); }
    }

    private void removePersistentLockdown() {
        try { Files.deleteIfExists(Path.of(SECURITY_CMD_DIR, "lockdown-persistent.sh")); }
        catch (Exception e) { log.warn("removePersistentLockdown: {}", e.getMessage()); }
    }

    /**
     * Escreve um script .cmd no volume compartilhado.
     * O security container tem um watcher que executa e remove esses scripts.
     */
    private void writeSecurityCmd(String name, String script) throws IOException {
        Path dir = Path.of(SECURITY_CMD_DIR);
        Files.createDirectories(dir);
        Path cmdFile = dir.resolve(name + "-" + System.currentTimeMillis() + ".cmd");
        Files.writeString(cmdFile, script, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        // Aguarda o security container executar (máx 5s)
        long start = System.currentTimeMillis();
        while (Files.exists(cmdFile) && System.currentTimeMillis() - start < 5000) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        if (Files.exists(cmdFile)) {
            log.warn("Security cmd não foi executado em 5s: {}", cmdFile);
        }
    }

    /**
     * Aplica ACL no Asterisk com política whitelist-only:
     * permit=IP1, permit=IP2, ..., deny=0.0.0.0/0
     */
    private void applyLockdownAcl(List<String> whitelist) throws IOException {
        Path aclPath = Path.of(asteriskConfigDir, "acl.conf");
        StringBuilder sb = new StringBuilder();
        sb.append("; AsteriskIA — ACL gerada automaticamente pelo modo lockdown\n");
        sb.append("; MODO LOCKDOWN ATIVO — apenas whitelist pode conectar\n");
        sb.append("[whitelist-only]\n");
        sb.append("type=acl\n");
        for (String ip : whitelist) {
            sb.append("permit=").append(ip).append("\n");
        }
        sb.append("deny=0.0.0.0/0\n");
        sb.append("deny=::/0\n");
        writeAtomic(aclPath, sb.toString());
        reloadAsteriskAcl();
    }

    private void restorePermissiveAcl() throws IOException {
        Path aclPath = Path.of(asteriskConfigDir, "acl.conf");
        writeAtomic(aclPath,
            "; AsteriskIA — ACL — modo normal (fail2ban ativo)\n" +
            "[blacklist]\ntype=acl\n");
        reloadAsteriskAcl();
    }

    private void reloadAsteriskAcl() {
        try {
            List<String> cmd = List.of(
                "asterisk", "-rx", "acl reload"
            );
            new ProcessBuilder(cmd).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("reloadAsteriskAcl: {}", e.getMessage());
        }
    }

    // ── Threats ───────────────────────────────────────────────────────────────

    @GetMapping("/threats")
    public ResponseEntity<List<Map<String,Object>>> threats() {
        List<Map<String,Object>> result = new ArrayList<>();
        for (String jail : MANAGED_JAILS) {
            try {
                String out = f2bExec("get", jail, "monitored");
                if (out == null || out.isBlank() || out.contains("No")) continue;
                for (String line : out.split("\n")) {
                    Map<String,Object> e = parseMonitoredLine(line.trim(), jail);
                    if (e != null) result.add(e);
                }
            } catch (Exception e) {
                log.debug("threats jail {}: {}", jail, e.getMessage());
            }
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/test-regex")
    public ResponseEntity<Map<String, Object>> testRegex(
            @RequestBody Map<String, String> body) {
        String regex = body.get("regex");
        int lines = parseInt(body.getOrDefault("lines", "200"));
        if (regex == null || regex.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Regex obrigatória."));
        try {
            List<String> log = tailAsteriskLog(lines);
            Pattern p = Pattern.compile(regex);
            List<String> matches = log.stream()
                .filter(l -> p.matcher(l).find()).limit(20).collect(Collectors.toList());
            return ResponseEntity.ok(Map.of(
                "matches", matches, "count", matches.size(), "tested", log.size()));
        } catch (PatternSyntaxException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Regex inválida: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    // =========================================================================
    // Privado — fail2ban via docker exec
    // =========================================================================

    /**
     * Executa fail2ban-client diretamente no backend usando o socket compartilhado.
     * O volume fail2ban_socket monta /var/run/fail2ban em ambos os containers
     * (security e backend), então o cliente pode falar com o daemon sem docker exec.
     */
    private String f2bExec(String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of(
                "fail2ban-client", "-s", "/var/run/fail2ban/fail2ban.sock"));
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining("\n"));
            }
            p.waitFor(15, TimeUnit.SECONDS);
            log.debug("f2b [{}]: {}", String.join(" ", args), out.trim());
            return out.trim();
        } catch (Exception e) {
            log.warn("f2bExec {}: {}", String.join(" ", args), e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    private boolean isF2bRunning() {
        try {
            String out = f2bExec("ping");
            return out != null && out.contains("pong");
        } catch (Exception e) { return false; }
    }

    private Map<String,Object> getJailInfo(String jail, boolean f2bRunning) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("name", jail);
        Map<String,String> cfg = parseJailConfig(jail);
        m.put("enabled",   "true".equals(cfg.getOrDefault("enabled", "false")));
        m.put("maxretry",  parseInt(cfg.getOrDefault("maxretry", "5")));
        m.put("findtime",  parseInt(cfg.getOrDefault("findtime", "30")));
        m.put("bantime",   parseInt(cfg.getOrDefault("bantime",  "86400")));
        m.put("banaction", cfg.getOrDefault("banaction", "iptables-multiport"));
        m.put("port",      cfg.getOrDefault("port", "5060,5061,8088"));
        if (f2bRunning) {
            String status = f2bExec("status", jail);
            m.put("currentlyBanned", parseBannedCount(status));
            m.put("totalFailed",     parseTotalFailed(status));
        } else {
            m.put("currentlyBanned", 0);
            m.put("totalFailed",     0);
        }
        return m;
    }

    private ResponseEntity<Map<String,Object>> toggleJail(
            String jail, boolean enable, HttpServletRequest req) {
        if (!MANAGED_JAILS.contains(jail))
            return ResponseEntity.badRequest().body(Map.of("message", "Jail desconhecido."));
        try {
            updateJailParam(jail, "enabled", enable ? "true" : "false");
            String reload = f2bExec("reload", jail);
            auditService.log(req, "SECURITY_JAIL_TOGGLE",
                jail + " " + (enable ? "habilitado" : "desabilitado"), true);
            return ResponseEntity.ok(Map.of(
                "message", jail + (enable ? " ativado." : " desativado."), "reload", reload));
        } catch (Exception e) {
            log.error("toggleJail {}: {}", jail, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Erro: " + e.getMessage()));
        }
    }

    // =========================================================================
    // Privado — leitura/escrita de configuração
    // =========================================================================

    private Map<String,String> parseJailConfig(String jail) {
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

    private void updateJailParam(String jail, String key, String value) throws IOException {
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
        writeAtomic(path, updated);
        log.info("Jail [{}] {} = {}", jail, key, value);
    }

    private String readJailConfig(String jail) {
        try {
            String content = Files.readString(
                Path.of(jailConfigDir, "asterisk.conf"), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                "\\[" + Pattern.quote(jail) + "\\][^\\[]*", Pattern.DOTALL).matcher(content);
            return m.find() ? m.group().strip() : "";
        } catch (Exception e) { return ""; }
    }

    private String readFilterRegex(String jail) {
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

    private void writeFilterRegex(String jail, String regex) throws IOException {
        Path path = Path.of(filterConfigDir, jail + ".conf");
        if (!Files.exists(path)) return;
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String newBlock = "failregex = " +
            regex.strip().replace("\n", "\n            ") + "\n\n";
        String updated = Pattern.compile("(?m)^failregex\\s*=.*?(?=^[a-z]|\\z)", Pattern.DOTALL)
            .matcher(content).replaceFirst(Matcher.quoteReplacement(newBlock));
        writeAtomic(path, updated);
    }

    private void writeAtomic(Path path, String content) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // =========================================================================
    // Privado — ACL Asterisk
    // =========================================================================

    private void addToAsteriskAcl(String ip) throws IOException {
        Path p = Path.of(asteriskConfigDir, "acl.conf");
        String content = Files.exists(p)
            ? Files.readString(p, StandardCharsets.UTF_8) : "[blacklist]\ntype=acl\n";
        if (!content.contains(ip)) {
            writeAtomic(p, content.stripTrailing() + "\ndeny=" + ip + "\n");
        }
    }

    private void removeFromAsteriskAcl(String ip) throws IOException {
        Path p = Path.of(asteriskConfigDir, "acl.conf");
        if (!Files.exists(p)) return;
        String updated = Files.readAllLines(p, StandardCharsets.UTF_8).stream()
            .filter(l -> !l.contains("deny=" + ip))
            .collect(Collectors.joining("\n")) + "\n";
        writeAtomic(p, updated);
    }

    // =========================================================================
    // Privado — persistência manual bans e whitelist
    // =========================================================================

    private String manualBansFile() { return securityDir + "/manual-bans.csv"; }
    private String whitelistFile()  { return securityDir + "/whitelist.txt";   }

    private List<Map<String,String>> readManualBans() {
        List<Map<String,String>> list = new ArrayList<>();
        try {
            Path p = Path.of(manualBansFile());
            if (!Files.exists(p)) return list;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(",", 4);
                Map<String,String> m = new LinkedHashMap<>();
                m.put("ip",     parts[0].trim());
                m.put("jail",   parts.length > 1 ? parts[1].trim() : "manual");
                m.put("note",   parts.length > 2 ? parts[2].trim() : "");
                m.put("ts",     parts.length > 3 ? parts[3].trim() : "");
                m.put("origin", "manual");
                list.add(m);
            }
        } catch (Exception e) { log.warn("readManualBans: {}", e.getMessage()); }
        return list;
    }

    private void saveManualBan(String ip, String note, String jail) {
        try {
            Path p = Path.of(manualBansFile());
            Files.createDirectories(p.getParent());
            String line = ip + "," + jail + "," + note.replace(",", "；") + "," + Instant.now() + "\n";
            Files.writeString(p, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { log.warn("saveManualBan: {}", e.getMessage()); }
    }

    private void removeManualBan(String ip) {
        try {
            Path p = Path.of(manualBansFile());
            if (!Files.exists(p)) return;
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.startsWith(ip + ",")).collect(Collectors.toList());
            Files.write(p, lines, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } catch (Exception e) { log.warn("removeManualBan: {}", e.getMessage()); }
    }

    private static final List<String> DEFAULT_WHITELIST =
        List.of("127.0.0.1/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    private List<String> readWhitelist() {
        try {
            Path p = Path.of(whitelistFile());
            if (!Files.exists(p)) return new ArrayList<>(DEFAULT_WHITELIST);
            return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .collect(Collectors.toList());
        } catch (Exception e) { return new ArrayList<>(DEFAULT_WHITELIST); }
    }

    private void writeWhitelist(List<String> ips) throws IOException {
        Path p = Path.of(whitelistFile());
        Files.createDirectories(p.getParent());
        writeAtomic(p, "# AsteriskIA — Lista branca\n" + String.join("\n", ips) + "\n");
    }

    private void updateIgnoreIp(List<String> whitelist) throws IOException {
        String ignoreIp = String.join(" ", whitelist);
        for (String jail : MANAGED_JAILS)
            updateJailParam(jail, "ignoreip", ignoreIp);
    }

    // =========================================================================
    // Privado — helpers de parse
    // =========================================================================

    private List<String> parseBannedIps(String f2bStatus) {
        List<String> ips = new ArrayList<>();
        if (f2bStatus == null || f2bStatus.isBlank()) return ips;
        Matcher m = Pattern.compile("Banned IP list:\\s*(.*)").matcher(f2bStatus);
        if (m.find()) {
            String raw = m.group(1).trim();
            if (!raw.isEmpty()) Arrays.stream(raw.split("\\s+")).forEach(ips::add);
        }
        return ips;
    }

    private int parseBannedCount(String status) {
        if (status == null) return 0;
        Matcher m = Pattern.compile("Currently banned:\\s*(\\d+)").matcher(status);
        return m.find() ? parseInt(m.group(1)) : 0;
    }

    private int parseTotalFailed(String status) {
        if (status == null) return 0;
        Matcher m = Pattern.compile("Total failed:\\s*(\\d+)").matcher(status);
        return m.find() ? parseInt(m.group(1)) : 0;
    }

    private Map<String,Object> parseMonitoredLine(String line, String jail) {
        Matcher m = Pattern.compile("(\\S+).*failures\\s*=\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE).matcher(line);
        if (!m.find()) return null;
        Map<String,Object> e = new LinkedHashMap<>();
        e.put("ip",       m.group(1));
        e.put("failures", parseInt(m.group(2)));
        e.put("jail",     jail);
        return e;
    }

    /** Chama o docker-helper (GET /asterisk/log) — antigo docker exec asteriskia-asterisk tail. */
    @SuppressWarnings("unchecked")
    private List<String> tailAsteriskLog(int lines) {
        String url = UriComponentsBuilder.fromHttpUrl(dockerHelperUrl + "/asterisk/log")
                .queryParam("lines", lines).toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalApiKey);
        ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = resp.getBody();
        return body != null ? (List<String>) body.getOrDefault("lines", List.of()) : List.of();
    }

    private boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        return ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}(/\\d{1,2})?$")
            || ip.matches("^[0-9a-fA-F:]+(/\\d{1,3})?$");
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private Map<String,String> mapOf(String... kv) {
        Map<String,String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
