package com.asteriskia.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * StatsTrunkAmiClient — status do tronco SIP via 'pjsip show contacts' no AMI, extraído de
 * StatsController (fase 8 da refatoração). Abre uma conexão TCP por consulta — sem pool, adequado à
 * baixa frequência de uso deste endpoint (status sob demanda pelo dashboard, não streaming).
 */
@Slf4j
@Component
public class StatsTrunkAmiClient {

    @Value("${app.asterisk.ami.host:asterisk}")
    private String amiHost;

    @Value("${app.asterisk.ami.port:5038}")
    private int amiPort;

    @Value("${app.asterisk.ami.user:asteriskia}")
    private String amiUser;

    @Value("${app.asterisk.ami.password}")
    private String amiPassword;

    private static final int AMI_TIMEOUT_MS = 8_000;

    public Map<String, Object> queryTrunkStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedAt", Instant.now().toString());
        try (Socket s = new Socket(amiHost, amiPort)) {
            s.setSoTimeout(AMI_TIMEOUT_MS);
            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter w =
                    new PrintWriter(
                            new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8),
                            true);
            r.readLine(); // banner

            sendAmiBlock(w, "Action", "Login", "Username", amiUser, "Secret", amiPassword);
            if (!readAmiBlock(r).contains("Success")) {
                result.put("status", "UNKNOWN");
                result.put("rttMs", -1);
                result.put("error", "ami_auth");
                return result;
            }

            sendAmiBlock(w, "Action", "Command", "Command", "pjsip show contacts");
            // O AMI envia Command em dois blocos: cabeçalho "Response: Success" + linhas "Output:".
            // Lemos diretamente até encontrar a linha terminadora do pjsip show contacts.
            String contacts = readCommandOutput(r);
            sendAmiBlock(w, "Action", "Logoff");

            result.put("status", "UNKNOWN");
            result.put("rttMs", -1);
            for (String line : contacts.split("\n")) {
                if (!line.contains("tronco-sip")) continue;
                if (line.contains("Avail") && !line.contains("Unavail")) {
                    result.put("status", "ONLINE");
                    result.put("rttMs", parseRttMs(line));
                } else {
                    result.put("status", "OFFLINE");
                    result.put("rttMs", -1);
                }
                break;
            }
        } catch (Exception e) {
            log.warn("trunk-status AMI error: {}", e.getMessage());
            result.put("status", "UNKNOWN");
            result.put("rttMs", -1);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private int parseRttMs(String line) {
        String[] parts = line.trim().split("\\s+");
        try {
            return (int) Double.parseDouble(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void sendAmiBlock(PrintWriter w, String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length - 1; i += 2)
            sb.append(kv[i]).append(": ").append(kv[i + 1]).append("\r\n");
        sb.append("\r\n");
        w.print(sb);
        w.flush();
    }

    private String readAmiBlock(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) break;
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /** Lê linhas do AMI até encontrar a sentinela de fim do 'pjsip show contacts'. */
    private String readCommandOutput(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append("\n");
            if (line.startsWith("Output: Objects found:") || line.contains("--END COMMAND--"))
                break;
        }
        return sb.toString();
    }
}
