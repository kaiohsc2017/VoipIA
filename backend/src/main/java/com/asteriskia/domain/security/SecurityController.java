package com.asteriskia.domain.security;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * SecurityController — endpoints REST de segurança (fail2ban, ACL, lockdown de rede, teste de regex
 * de log). A comunicação com fail2ban vive em {@link FailToBanClient}, a leitura/escrita de
 * asterisk.conf/filter.d em {@link JailConfigRepository}, a orquestração de jails (config + status
 * combinados, toggle, sincronismo de ignoreip) em {@link SecurityJailService}, a busca do log do
 * Asterisk via docker-helper em {@link AsteriskLogClient}, a gestão de ACL/lockdown de rede em
 * {@link AsteriskAclService}, a persistência de bans manuais/whitelist em {@link
 * SecurityListsRepository} e o teste de regex com timeout em {@link RegexTester} — extraídos deste
 * controller (achado de auditoria: arquivo > 800 linhas; SecurityListsRepository/RegexTester na
 * fase 7, SecurityJailService/AsteriskLogClient na fase 11) sem mudança de comportamento nem de
 * rota.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class SecurityController {

    private final AuditService auditService;
    private final FailToBanClient f2b;
    private final JailConfigRepository jailConfigRepo;
    private final AsteriskAclService aclService;
    private final SecurityListsRepository listsRepo;
    private final SecurityJailService jailService;
    private final AsteriskLogClient logClient;

    /**
     * Achado de segurança: banaction era gravado sem validação nenhuma em asterisk.conf. Allowlist
     * das ações realmente usadas neste stack (iptables/nftables — ver
     * security/config/jail.d/asterisk.conf).
     */
    private static final Set<String> ALLOWED_BANACTIONS =
            Set.of(
                    "iptables-multiport",
                    "iptables-allports",
                    "iptables",
                    "nftables-multiport",
                    "nftables-allports",
                    "nftables");

    // ── Status geral ──────────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean f2bRunning = jailService.isFail2banRunning();
        result.put("fail2banRunning", f2bRunning);

        int totalBanned = 0, activeJails = 0;
        List<Map<String, Object>> jails = jailService.allJailInfo(f2bRunning);
        for (Map<String, Object> info : jails) {
            if (Boolean.TRUE.equals(info.get("enabled"))) activeJails++;
            Object b = info.get("currentlyBanned");
            if (b instanceof Integer) totalBanned += (Integer) b;
        }
        result.put("jails", jails);
        result.put("activeJails", activeJails);
        result.put("totalBanned", totalBanned);
        result.put("whitelist", listsRepo.readWhitelist());
        return ResponseEntity.ok(result);
    }

    // ── Jails ─────────────────────────────────────────────────────────────────

    @GetMapping("/jails")
    public ResponseEntity<List<Map<String, Object>>> jails() {
        return ResponseEntity.ok(jailService.allJailInfo(jailService.isFail2banRunning()));
    }

    @GetMapping("/jails/{jail}")
    public ResponseEntity<Map<String, Object>> jailDetail(@PathVariable String jail) {
        if (!jailService.isManaged(jail))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Jail desconhecido: " + jail));
        Map<String, Object> info = jailService.jailInfo(jail, jailService.isFail2banRunning());
        info.put("filterRegex", jailConfigRepo.readFilterRegex(jail));
        info.put("jailConfig", jailConfigRepo.readJailConfig(jail));
        return ResponseEntity.ok(info);
    }

    @PutMapping("/jails/{jail}")
    public ResponseEntity<Map<String, Object>> updateJail(
            @PathVariable String jail,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        if (!jailService.isManaged(jail))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Jail desconhecido: " + jail));
        if (body.containsKey("banaction")
                && !ALLOWED_BANACTIONS.contains(String.valueOf(body.get("banaction"))))
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "message",
                                    "banaction inválido. Permitidos: " + ALLOWED_BANACTIONS));
        try {
            if (body.containsKey("maxretry"))
                jailConfigRepo.updateJailParam(
                        jail, "maxretry", String.valueOf(body.get("maxretry")));
            if (body.containsKey("findtime"))
                jailConfigRepo.updateJailParam(
                        jail, "findtime", String.valueOf(body.get("findtime")));
            if (body.containsKey("bantime"))
                jailConfigRepo.updateJailParam(
                        jail, "bantime", String.valueOf(body.get("bantime")));
            if (body.containsKey("banaction"))
                jailConfigRepo.updateJailParam(
                        jail, "banaction", String.valueOf(body.get("banaction")));
            if (body.containsKey("filterRegex"))
                jailConfigRepo.writeFilterRegex(jail, String.valueOf(body.get("filterRegex")));

            String reload = f2b.exec("reload", jail);
            auditService.log(
                    request,
                    "SECURITY_JAIL_UPDATE",
                    "Jail " + jail + " atualizado. Reload: " + reload,
                    true);
            return ResponseEntity.ok(Map.of("message", "Jail atualizado.", "reload", reload));
        } catch (Exception e) {
            log.error("updateJail {}: {}", jail, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao salvar configuração do jail."));
        }
    }

    @PostMapping("/jails/{jail}/enable")
    public ResponseEntity<Map<String, Object>> enableJail(
            @PathVariable String jail, HttpServletRequest req) {
        return toggleJail(jail, true, req);
    }

    @PostMapping("/jails/{jail}/disable")
    public ResponseEntity<Map<String, Object>> disableJail(
            @PathVariable String jail, HttpServletRequest req) {
        return toggleJail(jail, false, req);
    }

    // ── Ban / Unban ───────────────────────────────────────────────────────────

    @GetMapping("/banned")
    public ResponseEntity<List<Map<String, String>>> banned() {
        List<Map<String, String>> all = new ArrayList<>();
        for (String jail : SecurityJailService.MANAGED_JAILS) {
            for (String ip : f2b.parseBannedIps(f2b.exec("status", jail))) {
                all.add(mapOf("ip", ip, "jail", jail, "origin", "fail2ban"));
            }
        }
        all.addAll(listsRepo.readManualBans());
        return ResponseEntity.ok(all);
    }

    @PostMapping("/ban")
    public ResponseEntity<Map<String, Object>> ban(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        String ip = body.get("ip");
        String note = body.getOrDefault("note", "Bloqueio manual");
        String jail = body.getOrDefault("jail", "asterisk-auth");
        if (ip == null || ip.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "IP obrigatório"));
        if (!isValidIp(ip))
            return ResponseEntity.badRequest().body(Map.of("message", "IP inválido: " + ip));
        try {
            String f2bResult = f2b.exec("set", jail, "banip", ip);
            aclService.addToAsteriskAcl(ip);
            listsRepo.saveManualBan(ip, note, jail);
            auditService.log(request, "SECURITY_BAN", "IP banido: " + ip + " | " + note, true);
            return ResponseEntity.ok(
                    Map.of("message", "IP " + ip + " bloqueado.", "fail2ban", f2bResult));
        } catch (Exception e) {
            log.error("ban {}: {}", ip, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao banir IP."));
        }
    }

    @DeleteMapping("/ban/{ip}")
    public ResponseEntity<Map<String, Object>> unban(
            @PathVariable String ip,
            @RequestParam(defaultValue = "") String jail,
            HttpServletRequest request) {
        try {
            List<String> jailsToUnban =
                    jail.isBlank() ? SecurityJailService.MANAGED_JAILS : List.of(jail);
            List<String> results =
                    jailsToUnban.stream()
                            .map(j -> j + ": " + f2b.exec("set", j, "unbanip", ip))
                            .collect(Collectors.toList());
            aclService.removeFromAsteriskAcl(ip);
            listsRepo.removeManualBan(ip);
            auditService.log(request, "SECURITY_UNBAN", "IP desbloqueado: " + ip, true);
            return ResponseEntity.ok(
                    Map.of("message", "IP " + ip + " desbloqueado.", "results", results));
        } catch (Exception e) {
            log.error("unban {}: {}", ip, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao desbloquear IP."));
        }
    }

    // ── Whitelist ─────────────────────────────────────────────────────────────

    @GetMapping("/whitelist")
    public ResponseEntity<List<String>> whitelist() {
        return ResponseEntity.ok(listsRepo.readWhitelist());
    }

    @PostMapping("/whitelist")
    public ResponseEntity<Map<String, Object>> addWhitelist(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        String ip = body.get("ip");
        if (!isValidIp(ip))
            return ResponseEntity.badRequest().body(Map.of("message", "IP inválido: " + ip));
        try {
            List<String> list = listsRepo.readWhitelist();
            if (!list.contains(ip)) {
                list.add(ip);
                listsRepo.writeWhitelist(list);
                jailService.updateIgnoreIp(list);
                f2b.exec("reload");
            }
            aclService.reapplyLockdownIfActive(list);
            auditService.log(request, "SECURITY_WHITELIST_ADD", ip, true);
            return ResponseEntity.ok(Map.of("message", ip + " adicionado à lista branca."));
        } catch (Exception e) {
            log.error("addWhitelist {}: {}", ip, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao adicionar à lista branca."));
        }
    }

    @DeleteMapping("/whitelist/{ip}")
    public ResponseEntity<Map<String, Object>> removeWhitelist(
            @PathVariable String ip, HttpServletRequest request) {
        try {
            List<String> list = listsRepo.readWhitelist();
            list.remove(ip);
            listsRepo.writeWhitelist(list);
            jailService.updateIgnoreIp(list);
            f2b.exec("reload");
            aclService.reapplyLockdownIfActive(list);
            auditService.log(request, "SECURITY_WHITELIST_REMOVE", ip, true);
            return ResponseEntity.ok(Map.of("message", ip + " removido."));
        } catch (Exception e) {
            log.error("removeWhitelist {}: {}", ip, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao remover da lista branca."));
        }
    }

    // ── Lockdown mode — bloqueia tudo, libera apenas whitelist ──────────────

    @GetMapping("/lockdown")
    public ResponseEntity<Map<String, Object>> lockdownStatus() {
        boolean active = aclService.isLockdownActive();
        return ResponseEntity.ok(
                Map.of(
                        "active",
                        active,
                        "description",
                        active
                                ? "Modo lockdown ATIVO — apenas IPs da whitelist podem conectar ao SIP"
                                : "Modo normal — fail2ban monitora e bloqueia ameaças"));
    }

    @PostMapping("/lockdown/enable")
    public ResponseEntity<Map<String, Object>> enableLockdown(HttpServletRequest request) {
        try {
            List<String> whitelist = listsRepo.readWhitelist();
            aclService.applyLockdownIptables(whitelist);
            aclService.applyLockdownAcl(whitelist);
            aclService.writeLockdownFlag(true);
            auditService.log(
                    request,
                    "SECURITY_LOCKDOWN_ENABLE",
                    "Modo lockdown ativado. Whitelist: " + whitelist.size() + " IPs",
                    true);
            return ResponseEntity.ok(
                    Map.of(
                            "message",
                            "Lockdown ativado. Apenas IPs da whitelist podem conectar.",
                            "whitelistedIps",
                            whitelist.size()));
        } catch (Exception e) {
            log.error("enableLockdown: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao ativar lockdown."));
        }
    }

    @PostMapping("/lockdown/disable")
    public ResponseEntity<Map<String, Object>> disableLockdown(HttpServletRequest request) {
        try {
            aclService.removeLockdownIptables();
            aclService.restorePermissiveAcl();
            aclService.writeLockdownFlag(false);
            auditService.log(
                    request,
                    "SECURITY_LOCKDOWN_DISABLE",
                    "Modo lockdown desativado. Voltando ao modo fail2ban.",
                    true);
            return ResponseEntity.ok(
                    Map.of("message", "Lockdown desativado. Modo fail2ban ativo."));
        } catch (Exception e) {
            log.error("disableLockdown: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao desativar lockdown."));
        }
    }

    @PostMapping("/test-regex")
    public ResponseEntity<Map<String, Object>> testRegex(@RequestBody Map<String, String> body) {
        String regex = body.get("regex");
        int lines = SecurityFileUtils.parseInt(body.getOrDefault("lines", "200"));
        if (regex == null || regex.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Regex obrigatória."));
        try {
            List<String> log = logClient.tail(lines);
            Pattern p = Pattern.compile(regex);
            List<String> matches = RegexTester.runWithTimeout(p, log);
            return ResponseEntity.ok(
                    Map.of("matches", matches, "count", matches.size(), "tested", log.size()));
        } catch (PatternSyntaxException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Regex inválida: " + e.getMessage()));
        } catch (java.util.concurrent.TimeoutException e) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "message",
                                    "Regex demorou demais pra rodar (possível catastrophic"
                                            + " backtracking) — simplifique o padrão."));
        } catch (Exception e) {
            log.error("testRegex: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao testar regex."));
        }
    }

    // =========================================================================
    // Privado — orquestração de jail (delega a SecurityJailService)
    // =========================================================================

    private ResponseEntity<Map<String, Object>> toggleJail(
            String jail, boolean enable, HttpServletRequest req) {
        if (!jailService.isManaged(jail))
            return ResponseEntity.badRequest().body(Map.of("message", "Jail desconhecido."));
        try {
            String reload = jailService.toggleJail(jail, enable);
            auditService.log(
                    req,
                    "SECURITY_JAIL_TOGGLE",
                    jail + " " + (enable ? "habilitado" : "desabilitado"),
                    true);
            return ResponseEntity.ok(
                    Map.of(
                            "message",
                            jail + (enable ? " ativado." : " desativado."),
                            "reload",
                            reload));
        } catch (Exception e) {
            log.error("toggleJail {}: {}", jail, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao alterar estado do jail."));
        }
    }

    // =========================================================================
    // Privado — helpers diversos
    // =========================================================================

    private boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        return ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}(/\\d{1,2})?$")
                || ip.matches("^[0-9a-fA-F:]+(/\\d{1,3})?$");
    }

    private Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
