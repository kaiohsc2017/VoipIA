package com.asteriskia.domain.logs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
        try (Socket s = new Socket(amiHost, amiPort)) {
            s.setSoTimeout(AMI_TIMEOUT);
            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter w =
                    new PrintWriter(
                            new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8),
                            true);
            r.readLine();
            sendAmi(w, mapOf("Action", "Login", "Username", amiUser, "Secret", amiPassword));
            if (!readBlock(r).contains("Success")) return Map.of("ok", false, "error", "ami_auth");

            sendAmi(w, mapOf("Action", "Command", "Command", "core show uptime"));
            String uptime = readBlock(r);
            sendAmi(w, mapOf("Action", "Command", "Command", "core show channels count"));
            String channels = readBlock(r);
            sendAmi(w, mapOf("Action", "Command", "Command", "core show version"));
            String version = readBlock(r);
            sendAmi(w, mapOf("Action", "Command", "Command", "pjsip show endpoints"));
            String endpoints = readBlock(r);
            sendAmi(w, mapOf("Action", "Command", "Command", "pjsip show registrations"));
            String regs = readBlock(r);
            sendAmi(w, mapOf("Action", "Logoff"));

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

    private void sendAmi(PrintWriter w, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n");
        w.print(sb);
        w.flush();
    }

    private String readBlock(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) break;
            sb.append(line).append("\n");
        }
        return sb.toString();
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
