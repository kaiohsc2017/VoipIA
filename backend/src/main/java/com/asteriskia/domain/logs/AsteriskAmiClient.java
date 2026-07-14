package com.asteriskia.domain.logs;

import com.asteriskia.integration.ami.AmiSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AsteriskAmiClient — consulta de status via protocolo AMI (Asterisk Manager Interface), extraído
 * de LogsController (fase 4 da refatoração). Abre uma conexão TCP por consulta, faz login/roda
 * comandos/logoff — sem pool de conexão, adequado à baixa frequência de uso deste endpoint (status
 * sob demanda, não streaming).
 */
@Slf4j
@Component
public class AsteriskAmiClient {

    @Value("${app.asterisk.ami.host:asterisk}")
    private String amiHost;

    @Value("${app.asterisk.ami.port:5038}")
    private int amiPort;

    @Value("${app.asterisk.ami.user:asteriskia}")
    private String amiUser;

    @Value("${app.asterisk.ami.password}")
    private String amiPassword;

    private static final int AMI_TIMEOUT = 8_000;

    public Map<String, Object> fetchStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        try (AmiSession ami = AmiSession.connect(amiHost, amiPort, AMI_TIMEOUT)) {
            if (!ami.login(amiUser, amiPassword)) return Map.of("ok", false, "error", "ami_auth");

            ami.send(mapOf("Action", "Command", "Command", "core show uptime"));
            String uptime = ami.readBlock();
            ami.send(mapOf("Action", "Command", "Command", "core show channels count"));
            String channels = ami.readBlock();
            ami.send(mapOf("Action", "Command", "Command", "core show version"));
            String version = ami.readBlock();
            ami.send(mapOf("Action", "Command", "Command", "pjsip show endpoints"));
            String endpoints = ami.readBlock();
            ami.send(mapOf("Action", "Command", "Command", "pjsip show registrations"));
            String regs = ami.readBlock();
            ami.logoff();

            result.put("ok", true);
            result.put("uptime", extractValue(uptime, "System uptime:"));
            result.put("version", extractFirstLine(version));
            result.put("channels", extractChannelCount(channels));
            result.put("endpoints", parseEndpoints(endpoints));
            result.put("trunk", parseTrunk(regs));
        } catch (Exception e) {
            log.warn("AMI status: {}", e.getMessage());
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private String extractValue(String block, String key) {
        for (String l : block.split("\n"))
            if (l.contains(key)) return l.substring(l.indexOf(key) + key.length()).trim();
        return "N/A";
    }

    private String extractFirstLine(String block) {
        for (String l : block.split("\n"))
            if (!l.isBlank() && !l.startsWith("Response") && !l.startsWith("Output"))
                return l.trim();
        return "N/A";
    }

    private int extractChannelCount(String block) {
        for (String l : block.split("\n"))
            if (l.contains("active channel"))
                try {
                    return Integer.parseInt(l.trim().split(" ")[0]);
                } catch (Exception e) {
                    return 0;
                }
        return 0;
    }

    private List<Map<String, String>> parseEndpoints(String block) {
        List<Map<String, String>> list = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (line.isBlank()
                    || line.startsWith("Endpoint")
                    || line.startsWith("=")
                    || line.startsWith("Response")
                    || line.startsWith("Output")) continue;
            String[] p = line.trim().split("\\s+");
            if (p.length >= 2) {
                String name = p[0].contains("/") ? p[0].split("/")[0] : p[0];
                if (name.length() > 1 && !name.startsWith("-"))
                    list.add(Map.of("name", name, "status", p[p.length - 1]));
            }
        }
        return list;
    }

    private Map<String, String> parseTrunk(String block) {
        for (String l : block.split("\n"))
            if (l.contains("tronco-sip") || l.contains("Registered") || l.contains("Unregistered"))
                return Map.of(
                        "name",
                        "tronco-sip",
                        "status",
                        l.contains("Registered") && !l.contains("Unregistered")
                                ? "Registered"
                                : "Unregistered");
        return Map.of("name", "tronco-sip", "status", "Unknown");
    }
}
