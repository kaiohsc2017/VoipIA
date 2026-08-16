package com.asteriskia.domain.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AsteriskAclService — gestão de ACL do Asterisk e do modo lockdown de rede (nftables na chain
 * DOCKER-USER, aplicado via o container security que tem NET_ADMIN + network_mode: host).
 *
 * <p>Extraído de SecurityController (achado de auditoria: arquivo > 800 linhas).
 */
@Slf4j
@Service
public class AsteriskAclService {

    @Value("${app.security.security-dir:/opt/asteriskia/security}")
    private String securityDir;

    @Value("${app.asterisk.config-dir:/etc/asterisk}")
    private String asteriskConfigDir;

    // Diretório compartilhado com o container security (que tem NET_ADMIN + iptables)
    private static final String SECURITY_CMD_DIR = "/var/run/voipia-security";

    // ── Lockdown — status ────────────────────────────────────────────────────

    public boolean isLockdownActive() {
        try {
            return Files.exists(Path.of(securityDir, "lockdown.flag"));
        } catch (Exception e) {
            return false;
        }
    }

    public void writeLockdownFlag(boolean active) throws IOException {
        Path flag = Path.of(securityDir, "lockdown.flag");
        Files.createDirectories(flag.getParent());
        if (active) Files.writeString(flag, "active", StandardCharsets.UTF_8);
        else Files.deleteIfExists(flag);
    }

    /**
     * Quando a whitelist muda, re-aplica as regras se o lockdown estiver ativo. Chamado pelo
     * controller após addWhitelist / removeWhitelist.
     */
    public void reapplyLockdownIfActive(List<String> whitelist) {
        if (!isLockdownActive()) return;
        try {
            applyLockdownIptables(whitelist);
            applyLockdownAcl(whitelist);
            log.info("Lockdown re-aplicado com {} IPs na whitelist", whitelist.size());
        } catch (Exception e) {
            log.error("reapplyLockdownIfActive: {}", e.getMessage(), e);
        }
    }

    // ── Lockdown — nftables (DOCKER-USER) ────────────────────────────────────

    /**
     * Aplica lockdown via nft na chain DOCKER-USER. O Docker neste host usa nftables — a chain
     * DOCKER-USER nftables é processada para todo tráfego destinado a containers.
     */
    public void applyLockdownIptables(List<String> whitelist)
            throws IOException, InterruptedException {
        StringBuilder script = new StringBuilder("#!/bin/bash\n");
        script.append(
                "# Limpa regras anteriores da DOCKER-USER (exceto AMI que pode já existir)\n");
        script.append("nft flush chain ip filter DOCKER-USER 2>/dev/null || true\n");
        script.append("# Whitelist — ACCEPT para IPs autorizados\n");
        for (String ip : whitelist) {
            script.append("nft add rule ip filter DOCKER-USER ip saddr ")
                    .append(ip)
                    .append(" accept\n");
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

    public void removeLockdownIptables() {
        try {
            String script =
                    "#!/bin/bash\n"
                            + "nft flush chain ip filter DOCKER-USER 2>/dev/null || true\n"
                            + "echo '[lockdown] DOCKER-USER limpa'\n";
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
            Files.writeString(
                    p,
                    script,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.warn("writePersistentLockdown: {}", e.getMessage());
        }
    }

    private void removePersistentLockdown() {
        try {
            Files.deleteIfExists(Path.of(SECURITY_CMD_DIR, "lockdown-persistent.sh"));
        } catch (Exception e) {
            log.warn("removePersistentLockdown: {}", e.getMessage());
        }
    }

    /**
     * Escreve um script .cmd no volume compartilhado. O security container tem um watcher que
     * executa e remove esses scripts.
     */
    private void writeSecurityCmd(String name, String script) throws IOException {
        Path dir = Path.of(SECURITY_CMD_DIR);
        Files.createDirectories(dir);
        Path cmdFile = dir.resolve(name + "-" + System.currentTimeMillis() + ".cmd");
        Files.writeString(
                cmdFile,
                script,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        // Aguarda o security container executar (máx 5s)
        long start = System.currentTimeMillis();
        while (Files.exists(cmdFile) && System.currentTimeMillis() - start < 5000) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (Files.exists(cmdFile)) {
            log.warn("Security cmd não foi executado em 5s: {}", cmdFile);
        }
    }

    // ── ACL do Asterisk (acl.conf) ───────────────────────────────────────────

    /**
     * Aplica ACL no Asterisk com política whitelist-only: permit=IP1, permit=IP2, ...,
     * deny=0.0.0.0/0
     */
    public void applyLockdownAcl(List<String> whitelist) throws IOException {
        Path aclPath = Path.of(asteriskConfigDir, "acl.conf");
        StringBuilder sb = new StringBuilder();
        sb.append("; VoipIA — ACL gerada automaticamente pelo modo lockdown\n");
        sb.append("; MODO LOCKDOWN ATIVO — apenas whitelist pode conectar\n");
        sb.append("[whitelist-only]\n");
        sb.append("type=acl\n");
        for (String ip : whitelist) {
            sb.append("permit=").append(ip).append("\n");
        }
        sb.append("deny=0.0.0.0/0\n");
        sb.append("deny=::/0\n");
        SecurityFileUtils.writeAtomic(aclPath, sb.toString());
        reloadAsteriskAcl();
    }

    public void restorePermissiveAcl() throws IOException {
        Path aclPath = Path.of(asteriskConfigDir, "acl.conf");
        SecurityFileUtils.writeAtomic(
                aclPath,
                "; VoipIA — ACL — modo normal (fail2ban ativo)\n" + "[blacklist]\ntype=acl\n");
        reloadAsteriskAcl();
    }

    private void reloadAsteriskAcl() {
        try {
            List<String> cmd = List.of("asterisk", "-rx", "acl reload");
            new ProcessBuilder(cmd).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("reloadAsteriskAcl: {}", e.getMessage());
        }
    }

    public void addToAsteriskAcl(String ip) throws IOException {
        Path p = Path.of(asteriskConfigDir, "acl.conf");
        String content =
                Files.exists(p)
                        ? Files.readString(p, StandardCharsets.UTF_8)
                        : "[blacklist]\ntype=acl\n";
        if (!content.contains(ip)) {
            SecurityFileUtils.writeAtomic(p, content.stripTrailing() + "\ndeny=" + ip + "\n");
        }
    }

    public void removeFromAsteriskAcl(String ip) throws IOException {
        Path p = Path.of(asteriskConfigDir, "acl.conf");
        if (!Files.exists(p)) return;
        String updated =
                Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                                .filter(l -> !l.contains("deny=" + ip))
                                .collect(Collectors.joining("\n"))
                        + "\n";
        SecurityFileUtils.writeAtomic(p, updated);
    }
}
