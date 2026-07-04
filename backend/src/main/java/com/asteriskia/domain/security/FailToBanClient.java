package com.asteriskia.domain.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * FailToBanClient — comunicação com o daemon fail2ban via socket compartilhado
 * (volume fail2ban_socket, montado tanto no container security quanto no backend)
 * e parsing das respostas de texto do fail2ban-client.
 *
 * Extraído de SecurityController (achado de auditoria: arquivo > 800 linhas).
 */
@Slf4j
@Service
public class FailToBanClient {

    /**
     * Executa fail2ban-client diretamente no backend usando o socket compartilhado.
     * O volume fail2ban_socket monta /var/run/fail2ban em ambos os containers
     * (security e backend), então o cliente pode falar com o daemon sem docker exec.
     */
    public String exec(String... args) {
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

    public boolean isRunning() {
        try {
            String out = exec("ping");
            return out != null && out.contains("pong");
        } catch (Exception e) { return false; }
    }

    public List<String> parseBannedIps(String f2bStatus) {
        List<String> ips = new ArrayList<>();
        if (f2bStatus == null || f2bStatus.isBlank()) return ips;
        Matcher m = Pattern.compile("Banned IP list:\\s*(.*)").matcher(f2bStatus);
        if (m.find()) {
            String raw = m.group(1).trim();
            if (!raw.isEmpty()) Arrays.stream(raw.split("\\s+")).forEach(ips::add);
        }
        return ips;
    }

    public int parseBannedCount(String status) {
        if (status == null) return 0;
        Matcher m = Pattern.compile("Currently banned:\\s*(\\d+)").matcher(status);
        return m.find() ? SecurityFileUtils.parseInt(m.group(1)) : 0;
    }

    public int parseTotalFailed(String status) {
        if (status == null) return 0;
        Matcher m = Pattern.compile("Total failed:\\s*(\\d+)").matcher(status);
        return m.find() ? SecurityFileUtils.parseInt(m.group(1)) : 0;
    }
}
