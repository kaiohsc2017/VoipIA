package com.asteriskia.domain;

import com.asteriskia.integration.ami.AmiSession;
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
        try (AmiSession ami = AmiSession.connect(amiHost, amiPort, AMI_TIMEOUT_MS)) {
            if (!ami.login(amiUser, amiPassword)) {
                result.put("status", "UNKNOWN");
                result.put("rttMs", -1);
                result.put("error", "ami_auth");
                return result;
            }

            ami.send(Map.of("Action", "Command", "Command", "pjsip show contacts"));
            // O AMI envia Command em dois blocos: cabeçalho "Response: Success" + linhas "Output:".
            // Lemos diretamente até encontrar a linha terminadora do pjsip show contacts.
            String contacts =
                    ami.readUntil(
                            line ->
                                    line.startsWith("Output: Objects found:")
                                            || line.contains("--END COMMAND--"));
            ami.logoff();

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
}
