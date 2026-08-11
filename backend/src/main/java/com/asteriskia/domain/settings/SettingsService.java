package com.asteriskia.domain.settings;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * SettingsService — Leitura, escrita e aplicação do arquivo .env do sistema.
 *
 * <p>A mecânica de arquivo (parse/rewrite/backup) vive em {@link EnvFileStore} e o apply assíncrono
 * (docker compose up via docker-helper) vive em {@link SettingsApplyJobService} — extraídos na fase
 * 9 da refatoração. Esta classe mantém a orquestração de negócio: validação de chave, resolução da
 * máscara de segredo e registro de histórico.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingsHistoryRepository historyRepository;
    private final EnvFileStore envFileStore;
    private final SettingsApplyJobService applyJobService;

    /** Token sentinela enviado pelo frontend quando o campo não foi alterado. */
    private static final String MASK_SENTINEL = "••••••••";

    /** Formato aceito de chave de variável de ambiente (identificador maiúsculo). */
    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    // -------------------------------------------------------------------------
    // Jobs assíncronos de apply
    // -------------------------------------------------------------------------

    /** Reinicia todos os serviços (comportamento legado). */
    public String startApplyAsync() {
        return applyJobService.startApplyAsync(List.of());
    }

    /** Reinicia apenas os serviços listados (lista vazia = todos). */
    public String startApplyAsync(List<String> services) {
        return applyJobService.startApplyAsync(services);
    }

    /** Retorna o estado atual de um job de apply. */
    public Optional<SettingsApplyJobService.ApplyJob> getApplyStatus(String jobId) {
        return applyJobService.getApplyStatus(jobId);
    }

    // -------------------------------------------------------------------------
    // Leitura
    // -------------------------------------------------------------------------

    /** Lê o arquivo .env e retorna uma lista ordenada de entradas. */
    public List<EnvEntry> readSettings() throws IOException {
        return envFileStore.readEntries();
    }

    /**
     * Retorna apenas os campos chave=valor como Map (sem comentários/blanks). Campos secretos são
     * mascarados.
     */
    public Map<String, Object> readAsMap() throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (EnvEntry e : readSettings()) {
            if (e.type() == EnvEntry.Type.FIELD) {
                result.put(
                        e.key(),
                        Map.of(
                                "value", e.value(),
                                "isSecret", e.isSecret()));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Escrita
    // -------------------------------------------------------------------------

    /**
     * Reescreve o arquivo .env preservando comentários e ordem originais. Cria backup antes de
     * sobrescrever e registra histórico de alterações.
     *
     * @param updates Map de key -> novo valor
     * @param changedBy Usuário que fez a alteração (do JWT)
     * @param ipAddress IP de origem da requisição
     */
    public void writeSettings(Map<String, String> updates, String changedBy, String ipAddress)
            throws IOException {
        // Achado de segurança: nenhuma validação de chave/valor antes de gravar o
        // .env real de produção — uma chave fora do formato de identificador ou um
        // valor com \r/\n corrompe o arquivo (linha extra interpretada como outra
        // variável). Valida tudo ANTES de tocar no disco (backup incluído).
        for (String key : updates.keySet()) {
            if (!ENV_KEY_PATTERN.matcher(key).matches()) {
                throw new IllegalArgumentException("Chave de configuração inválida: " + key);
            }
        }

        // Lê o estado atual para preservar valores mascarados que não foram alterados
        Map<String, String> current = envFileStore.readRaw();

        // Cria backup antes de modificar
        envFileStore.backup();

        // Resolve os valores finais: se o frontend enviou a máscara, usa o atual
        Map<String, String> resolved = new LinkedHashMap<>();
        updates.forEach(
                (k, v) -> {
                    if (MASK_SENTINEL.equals(v)) {
                        resolved.put(k, current.getOrDefault(k, ""));
                    } else {
                        resolved.put(k, v == null ? "" : v.replace("\r", "").replace("\n", ""));
                    }
                });

        envFileStore.rewrite(resolved);

        // Registra histórico de alterações (apenas chaves que mudaram)
        recordHistory(current, resolved, changedBy, ipAddress);
    }

    /** Sobrecarga sem contexto de requisição (compatibilidade). */
    public void writeSettings(Map<String, String> updates) throws IOException {
        writeSettings(updates, "admin", "unknown");
    }

    // -------------------------------------------------------------------------
    // Histórico de alterações
    // -------------------------------------------------------------------------

    /**
     * Registra no banco todas as chaves cujo valor efetivamente mudou. Campos secretos são gravados
     * mascarados.
     */
    private void recordHistory(
            Map<String, String> current,
            Map<String, String> resolved,
            String changedBy,
            String ipAddress) {
        List<SettingsHistory> records = new ArrayList<>();
        resolved.forEach(
                (key, newVal) -> {
                    String oldVal = current.get(key);
                    // Compara valores; pula se for igual
                    if (!Objects.equals(oldVal, newVal)) {
                        boolean secret = EnvFileStore.isSecretKey(key);
                        records.add(
                                SettingsHistory.builder()
                                        .changedAt(OffsetDateTime.now())
                                        .changedBy(changedBy != null ? changedBy : "admin")
                                        .envKey(key)
                                        .oldValue(
                                                secret
                                                        ? (oldVal != null
                                                                ? EnvFileStore.MASK_DISPLAY
                                                                : null)
                                                        : oldVal)
                                        .newValue(secret ? EnvFileStore.MASK_DISPLAY : newVal)
                                        .ipAddress(ipAddress)
                                        .build());
                    }
                });

        if (!records.isEmpty()) {
            historyRepository.saveAll(records);
            log.info(
                    "Histórico: {} alteração(ões) registrada(s) por {}", records.size(), changedBy);
        }
    }

    /** Retorna o histórico de alterações (mais recentes primeiro). */
    public List<SettingsHistory> getHistory(int limit) {
        return historyRepository.findAllByOrderByChangedAtDesc(
                PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }
}
