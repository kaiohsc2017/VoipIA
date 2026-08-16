package com.asteriskia.domain.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * SecurityListsRepository — leitura/escrita dos bans manuais (manual-bans.csv) e da whitelist
 * (whitelist.txt) do módulo de segurança. Extraído de SecurityController (fase 7 da refatoração),
 * mesmo racional da extração de JailConfigRepository/AsteriskAclService/FailToBanClient na
 * auditoria anterior.
 */
@Slf4j
@Service
public class SecurityListsRepository {

    @Value("${app.security.security-dir:/opt/asteriskia/security}")
    private String securityDir;

    private static final List<String> DEFAULT_WHITELIST =
            List.of("127.0.0.1/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    private String manualBansFile() {
        return securityDir + "/manual-bans.csv";
    }

    private String whitelistFile() {
        return securityDir + "/whitelist.txt";
    }

    public List<Map<String, String>> readManualBans() {
        List<Map<String, String>> list = new ArrayList<>();
        try {
            Path p = Path.of(manualBansFile());
            if (!Files.exists(p)) return list;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(",", 4);
                Map<String, String> m = new LinkedHashMap<>();
                m.put("ip", parts[0].trim());
                m.put("jail", parts.length > 1 ? parts[1].trim() : "manual");
                m.put("note", parts.length > 2 ? parts[2].trim() : "");
                m.put("ts", parts.length > 3 ? parts[3].trim() : "");
                m.put("origin", "manual");
                list.add(m);
            }
        } catch (Exception e) {
            log.warn("readManualBans: {}", e.getMessage());
        }
        return list;
    }

    public void saveManualBan(String ip, String note, String jail) {
        try {
            Path p = Path.of(manualBansFile());
            Files.createDirectories(p.getParent());
            String line =
                    ip + "," + jail + "," + note.replace(",", "；") + "," + Instant.now() + "\n";
            Files.writeString(
                    p,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("saveManualBan: {}", e.getMessage());
        }
    }

    public void removeManualBan(String ip) {
        try {
            Path p = Path.of(manualBansFile());
            if (!Files.exists(p)) return;
            List<String> lines =
                    Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                            .filter(l -> !l.startsWith(ip + ","))
                            .collect(Collectors.toList());
            Files.write(
                    p,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE);
        } catch (Exception e) {
            log.warn("removeManualBan: {}", e.getMessage());
        }
    }

    public List<String> readWhitelist() {
        try {
            Path p = Path.of(whitelistFile());
            if (!Files.exists(p)) return new ArrayList<>(DEFAULT_WHITELIST);
            return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return new ArrayList<>(DEFAULT_WHITELIST);
        }
    }

    public void writeWhitelist(List<String> ips) throws IOException {
        Path p = Path.of(whitelistFile());
        Files.createDirectories(p.getParent());
        SecurityFileUtils.writeAtomic(
                p, "# VoipIA — Lista branca\n" + String.join("\n", ips) + "\n");
    }
}
