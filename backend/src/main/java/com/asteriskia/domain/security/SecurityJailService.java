package com.asteriskia.domain.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SecurityJailService — orquestração das 3 jails do fail2ban (leitura combinada de config + status,
 * habilitar/desabilitar, sincronizar ignoreip com a whitelist), extraído de SecurityController
 * (fase 11 da refatoração). Combina {@link FailToBanClient} (status/reload) com {@link
 * JailConfigRepository} (leitura/escrita do asterisk.conf) sem expor nenhum dos dois diretamente ao
 * controller para essas operações.
 */
@Component
@RequiredArgsConstructor
public class SecurityJailService {

    private final FailToBanClient f2b;
    private final JailConfigRepository jailConfigRepo;

    public static final List<String> MANAGED_JAILS =
            List.of("asterisk-auth", "asterisk-scan", "asterisk-flood");

    public boolean isManaged(String jail) {
        return MANAGED_JAILS.contains(jail);
    }

    public boolean isFail2banRunning() {
        return f2b.isRunning();
    }

    public Map<String, Object> jailInfo(String jail, boolean f2bRunning) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", jail);
        Map<String, String> cfg = jailConfigRepo.parseJailConfig(jail);
        m.put("enabled", "true".equals(cfg.getOrDefault("enabled", "false")));
        m.put("maxretry", SecurityFileUtils.parseInt(cfg.getOrDefault("maxretry", "5")));
        m.put("findtime", SecurityFileUtils.parseInt(cfg.getOrDefault("findtime", "30")));
        m.put("bantime", SecurityFileUtils.parseInt(cfg.getOrDefault("bantime", "86400")));
        m.put("banaction", cfg.getOrDefault("banaction", "iptables-multiport"));
        m.put("port", cfg.getOrDefault("port", "5060,5061,8088"));
        if (f2bRunning) {
            String status = f2b.exec("status", jail);
            m.put("currentlyBanned", f2b.parseBannedCount(status));
            m.put("totalFailed", f2b.parseTotalFailed(status));
        } else {
            m.put("currentlyBanned", 0);
            m.put("totalFailed", 0);
        }
        return m;
    }

    public List<Map<String, Object>> allJailInfo(boolean f2bRunning) {
        return MANAGED_JAILS.stream()
                .map(j -> jailInfo(j, f2bRunning))
                .collect(Collectors.toList());
    }

    /** Habilita/desabilita a jail e força um reload do fail2ban. Devolve a saída do reload. */
    public String toggleJail(String jail, boolean enable) throws IOException {
        jailConfigRepo.updateJailParam(jail, "enabled", enable ? "true" : "false");
        return f2b.exec("reload", jail);
    }

    /** Propaga a whitelist atual para o ignoreip de todas as jails gerenciadas. */
    public void updateIgnoreIp(List<String> whitelist) throws IOException {
        String ignoreIp = String.join(" ", whitelist);
        for (String jail : MANAGED_JAILS) {
            jailConfigRepo.updateJailParam(jail, "ignoreip", ignoreIp);
        }
    }
}
