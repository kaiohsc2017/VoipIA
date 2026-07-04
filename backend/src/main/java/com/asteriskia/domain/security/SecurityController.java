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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.*;

/**
 * SecurityController — endpoints REST de segurança (fail2ban, ACL, lockdown de
 * rede, teste de regex de log). A comunicação com fail2ban vive em
 * {@link FailToBanClient}, a leitura/escrita de asterisk.conf/filter.d em
 * {@link JailConfigRepository}, e a gestão de ACL/lockdown de rede em
 * {@link AsteriskAclService} — extraídos deste controller (achado de auditoria:
 * arquivo > 800 linhas) sem mudança de comportamento nem de rota.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class SecurityController {

    private final AuditService auditService;
    private final RestTemplate restTemplate;
    private final FailToBanClient f2b;
    private final JailConfigRepository jailConfigRepo;
    private final AsteriskAclService aclService;

    // Docker Helper — único container com acesso ao docker.sock (F-CRIT-10).
    // Este controller não roda mais 'docker exec' via ProcessBuilder.
    @Value("${app.docker-helper.url}")
    private String dockerHelperUrl;

    @Value("${app.internal-api-key}")
    private String internalApiKey;

    @Value("${app.security.security-dir:/opt/asteriskia/security}")
    private String securityDir;

    private static final List<String> MANAGED_JAILS =
        List.of("asterisk-auth", "asterisk-scan", "asterisk-flood");

    /**
     * Achado de segurança: banaction era gravado sem validação nenhuma em
     * asterisk.conf. Allowlist das ações realmente usadas neste stack
     * (iptables/nftables — ver security/config/jail.d/asterisk.conf).
     */
    private static final Set<String> ALLOWED_BANACTIONS = Set.of(
        "iptables-multiport", "iptables-allports", "iptables",
        "nftables-multiport", "nftables-allports", "nftables"
    );

    // ── Status geral ──────────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean f2bRunning = f2b.isRunning();
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
        boolean f2bRunning = f2b.isRunning();
        return ResponseEntity.ok(MANAGED_JAILS.stream()
            .map(j -> getJailInfo(j, f2bRunning)).collect(Collectors.toList()));
    }

    @GetMapping("/jails/{jail}")
    public ResponseEntity<Map<String, Object>> jailDetail(@PathVariable String jail) {
        if (!MANAGED_JAILS.contains(jail))
            return ResponseEntity.badRequest().body(Map.of("message", "Jail desconhecido: " + jail));
        Map<String,Object> info = getJailInfo(jail, f2b.isRunning());
        info.put("filterRegex", jailConfigRepo.readFilterRegex(jail));
        info.put("jailConfig",  jailConfigRepo.readJailConfig(jail));
        return ResponseEntity.ok(info);
    }

    @PutMapping("/jails/{jail}")
    public ResponseEntity<Map<String, Object>> updateJail(
            @PathVariable String jail,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        if (!MANAGED_JAILS.contains(jail))
            return ResponseEntity.badRequest().body(Map.of("message", "Jail desconhecido: " + jail));
        if (body.containsKey("banaction") && !ALLOWED_BANACTIONS.contains(String.valueOf(body.get("banaction"))))
            return ResponseEntity.badRequest().body(Map.of("message",
                "banaction inválido. Permitidos: " + ALLOWED_BANACTIONS));
        try {
            if (body.containsKey("maxretry"))
                jailConfigRepo.updateJailParam(jail, "maxretry", String.valueOf(body.get("maxretry")));
            if (body.containsKey("findtime"))
                jailConfigRepo.updateJailParam(jail, "findtime",  String.valueOf(body.get("findtime")));
            if (body.containsKey("bantime"))
                jailConfigRepo.updateJailParam(jail, "bantime",   String.valueOf(body.get("bantime")));
            if (body.containsKey("banaction"))
                jailConfigRepo.updateJailParam(jail, "banaction", String.valueOf(body.get("banaction")));
            if (body.containsKey("filterRegex"))
                jailConfigRepo.writeFilterRegex(jail, String.valueOf(body.get("filterRegex")));

            String reload = f2b.exec("reload", jail);
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
            for (String ip : f2b.parseBannedIps(f2b.exec("status", jail))) {
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
            String f2bResult = f2b.exec("set", jail, "banip", ip);
            aclService.addToAsteriskAcl(ip);
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
                .map(j -> j + ": " + f2b.exec("set", j, "unbanip", ip))
                .collect(Collectors.toList());
            aclService.removeFromAsteriskAcl(ip);
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
                f2b.exec("reload");
            }
            aclService.reapplyLockdownIfActive(list);
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
            f2b.exec("reload");
            aclService.reapplyLockdownIfActive(list);
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
        boolean active = aclService.isLockdownActive();
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
            aclService.applyLockdownIptables(whitelist);
            aclService.applyLockdownAcl(whitelist);
            aclService.writeLockdownFlag(true);
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
            aclService.removeLockdownIptables();
            aclService.restorePermissiveAcl();
            aclService.writeLockdownFlag(false);
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

    @PostMapping("/test-regex")
    public ResponseEntity<Map<String, Object>> testRegex(
            @RequestBody Map<String, String> body) {
        String regex = body.get("regex");
        int lines = SecurityFileUtils.parseInt(body.getOrDefault("lines", "200"));
        if (regex == null || regex.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Regex obrigatória."));
        try {
            List<String> log = tailAsteriskLog(lines);
            Pattern p = Pattern.compile(regex);
            List<String> matches = runRegexWithTimeout(p, log);
            return ResponseEntity.ok(Map.of(
                "matches", matches, "count", matches.size(), "tested", log.size()));
        } catch (PatternSyntaxException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Regex inválida: " + e.getMessage()));
        } catch (java.util.concurrent.TimeoutException e) {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Regex demorou demais pra rodar (possível catastrophic backtracking) — simplifique o padrão."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Achado de segurança (ReDoS, low): regex vinda do cliente rodava sem timeout —
     * uma regex com catastrophic backtracking travava a thread indefinidamente
     * (endpoint é admin-only, então o impacto é auto-DoS, mas ainda vale limitar).
     * Roda numa thread dedicada e interrompível; envolve cada linha numa
     * CharSequence que verifica a interrupção a cada charAt(), já que o motor de
     * regex do Java não respeita Thread.interrupt() sozinho.
     */
    private List<String> runRegexWithTimeout(Pattern p, List<String> log)
            throws java.util.concurrent.TimeoutException {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> log.stream()
                .filter(l -> p.matcher(new InterruptibleCharSequence(l)).find())
                .limit(20).collect(Collectors.toList()));
            return future.get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class InterruptibleCharSequence implements CharSequence {
        private final CharSequence inner;
        InterruptibleCharSequence(CharSequence inner) { this.inner = inner; }
        @Override public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) throw new RuntimeException("Regex interrompida por timeout");
            return inner.charAt(index);
        }
        @Override public int length() { return inner.length(); }
        @Override public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(inner.subSequence(start, end));
        }
        @Override public String toString() { return inner.toString(); }
    }

    // =========================================================================
    // Privado — orquestração de jail (usa FailToBanClient + JailConfigRepository)
    // =========================================================================

    private Map<String,Object> getJailInfo(String jail, boolean f2bRunning) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("name", jail);
        Map<String,String> cfg = jailConfigRepo.parseJailConfig(jail);
        m.put("enabled",   "true".equals(cfg.getOrDefault("enabled", "false")));
        m.put("maxretry",  SecurityFileUtils.parseInt(cfg.getOrDefault("maxretry", "5")));
        m.put("findtime",  SecurityFileUtils.parseInt(cfg.getOrDefault("findtime", "30")));
        m.put("bantime",   SecurityFileUtils.parseInt(cfg.getOrDefault("bantime",  "86400")));
        m.put("banaction", cfg.getOrDefault("banaction", "iptables-multiport"));
        m.put("port",      cfg.getOrDefault("port", "5060,5061,8088"));
        if (f2bRunning) {
            String status = f2b.exec("status", jail);
            m.put("currentlyBanned", f2b.parseBannedCount(status));
            m.put("totalFailed",     f2b.parseTotalFailed(status));
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
            jailConfigRepo.updateJailParam(jail, "enabled", enable ? "true" : "false");
            String reload = f2b.exec("reload", jail);
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
        SecurityFileUtils.writeAtomic(p, "# AsteriskIA — Lista branca\n" + String.join("\n", ips) + "\n");
    }

    private void updateIgnoreIp(List<String> whitelist) throws IOException {
        String ignoreIp = String.join(" ", whitelist);
        for (String jail : MANAGED_JAILS)
            jailConfigRepo.updateJailParam(jail, "ignoreip", ignoreIp);
    }

    // =========================================================================
    // Privado — helpers diversos
    // =========================================================================

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

    private Map<String,String> mapOf(String... kv) {
        Map<String,String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
