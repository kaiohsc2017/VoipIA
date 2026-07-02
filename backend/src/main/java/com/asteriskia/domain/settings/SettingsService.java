package com.asteriskia.domain.settings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SettingsService — Leitura, escrita e aplicação do arquivo .env do sistema.
 *
 * Fase 12 — adicionado:
 *  - backupEnv()      → cria .env.bak-YYYYMMDD-HHmmss antes de sobrescrever (máx 10)
 *  - recordHistory()  → grava alterações de chave na tabela settings_history
 *
 * O apply permanece ASSÍNCRONO:
 *   1. startApplyAsync()    → lança Thread virtual, retorna jobId (UUID)
 *   2. getApplyStatus(jobId) → retorna estado + log acumulado
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingsHistoryRepository historyRepository;
    private final WebClient.Builder webClientBuilder;

    /** Caminho do .env dentro do container (mapeado via volume). */
    @Value("${app.settings.file-path:/opt/asteriskia/env/.env}")
    private String settingsFilePath;

    /** URL do docker-helper — único container com acesso ao docker.sock (F-CRIT-10). */
    @Value("${app.docker-helper.url}")
    private String dockerHelperUrl;

    @Value("${app.internal-api-key}")
    private String internalApiKey;

    /** Token sentinela enviado pelo frontend quando o campo não foi alterado. */
    private static final String MASK_SENTINEL = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
    private static final String MASK_DISPLAY  = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";

    /** Máximo de backups do .env a manter. */
    private static final int MAX_BACKUPS = 10;

    /** Conjunto de chaves cujos valores não devem ser expostos no GET. */
    static final Set<String> SECRET_KEYS = Set.of(
            "SIP_TRUNK_PASSWORD",
            "GEMINI_API_KEY",
            "JIRA_API_TOKEN",
            "ZABBIX_PASSWORD",
            "TELEGRAM_BOT_TOKEN",
            "BACKEND_JWT_SECRET",
            "INTERNAL_API_KEY",
            "ADMIN_PASSWORD",
            "POSTGRES_PASSWORD",
            "AST_AMI_PASSWORD",
            "GRAFANA_ADMIN_PASSWORD",
            "VITE_SIP_PASSWORD"
    );

    // -------------------------------------------------------------------------
    // Jobs assíncronos de apply
    // -------------------------------------------------------------------------

    public enum JobStatus { RUNNING, DONE, ERROR }

    /** Estado de um job de apply em andamento ou concluído. */
    public static class ApplyJob {
        private final String id;
        private volatile JobStatus status = JobStatus.RUNNING;
        private final StringBuilder log   = new StringBuilder();

        ApplyJob(String id) { this.id = id; }

        synchronized void appendLog(String line) { log.append(line); }
        void setStatus(JobStatus s)              { this.status = s; }

        public String    getId()     { return id; }
        public JobStatus getStatus() { return status; }
        public synchronized String getLog() { return log.toString(); }
    }

    /** Mapa de jobs ativos/concluídos (TTL não implementado — suficiente para sessão). */
    private final ConcurrentHashMap<String, ApplyJob> jobs = new ConcurrentHashMap<>();

    /**
     * Inicia o apply de forma assíncrona via Thread virtual (Java 21).
     * Cria backup do .env antes de reiniciar.
     */
    /** Reinicia todos os serviços (comportamento legado). */
    public String startApplyAsync() { return startApplyAsync(List.of()); }

    /** Reinicia apenas os serviços listados (lista vazia = todos). */
    public String startApplyAsync(List<String> services) {
        String jobId = UUID.randomUUID().toString();
        ApplyJob job = new ApplyJob(jobId);
        jobs.put(jobId, job);
        log.info("Apply iniciado assincronamente, jobId={}", jobId);

        Thread.ofVirtual().name("apply-" + jobId).start(() -> {
            try {
                runApply(job, services);
                job.setStatus(JobStatus.DONE);
            } catch (Exception e) {
                log.error("Erro no apply assíncrono jobId={}: {}", jobId, e.getMessage(), e);
                job.appendLog("\n\u274c Erro: " + e.getMessage());
                job.setStatus(JobStatus.ERROR);
            }
        });

        return jobId;
    }

    /**
     * Retorna o estado atual de um job de apply.
     */
    public Optional<ApplyJob> getApplyStatus(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    // -------------------------------------------------------------------------
    // Leitura
    // -------------------------------------------------------------------------

    /**
     * Lê o arquivo .env e retorna uma lista ordenada de entradas.
     */
    public List<EnvEntry> readSettings() throws IOException {
        Path path = Path.of(settingsFilePath);
        if (!Files.exists(path)) {
            log.warn("Arquivo .env não encontrado em {}. Retornando lista vazia.", settingsFilePath);
            return List.of();
        }

        List<EnvEntry> entries = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                entries.add(EnvEntry.blank());
            } else if (trimmed.startsWith("#")) {
                entries.add(EnvEntry.comment(trimmed));
            } else {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key   = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    boolean secret = SECRET_KEYS.contains(key);
                    entries.add(EnvEntry.field(key, secret ? MASK_DISPLAY : value, secret));
                }
            }
        }
        return entries;
    }

    /**
     * Retorna apenas os campos chave=valor como Map (sem comentários/blanks).
     * Campos secretos são mascarados.
     */
    public Map<String, Object> readAsMap() throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (EnvEntry e : readSettings()) {
            if (e.type() == EnvEntry.Type.FIELD) {
                result.put(e.key(), Map.of(
                        "value",    e.value(),
                        "isSecret", e.isSecret()
                ));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Escrita
    // -------------------------------------------------------------------------

    /**
     * Reescreve o arquivo .env preservando comentários e ordem originais.
     * Cria backup antes de sobrescrever e registra histórico de alterações.
     *
     * @param updates  Map de key -> novo valor
     * @param changedBy  Usuário que fez a alteração (do JWT)
     * @param ipAddress  IP de origem da requisição
     */
    public void writeSettings(Map<String, String> updates, String changedBy, String ipAddress)
            throws IOException {
        Path path = Path.of(settingsFilePath);

        // Lê o estado atual para preservar valores mascarados que não foram alterados
        Map<String, String> current = readCurrentRaw(path);

        // Cria backup antes de modificar
        backupEnv(path);

        // Resolve os valores finais: se o frontend enviou a máscara, usa o atual
        Map<String, String> resolved = new LinkedHashMap<>();
        updates.forEach((k, v) -> {
            if (MASK_SENTINEL.equals(v)) {
                resolved.put(k, current.getOrDefault(k, ""));
            } else {
                resolved.put(k, v);
            }
        });

        // Reescreve o arquivo linha a linha
        List<String> lines = Files.exists(path)
                ? Files.readAllLines(path, StandardCharsets.UTF_8)
                : new ArrayList<>();

        List<String> output  = new ArrayList<>();
        Set<String>  written = new LinkedHashSet<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                output.add(line);
            } else {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    if (resolved.containsKey(key)) {
                        output.add(key + "=" + resolved.get(key));
                        written.add(key);
                    } else {
                        output.add(line);
                    }
                } else {
                    output.add(line);
                }
            }
        }

        // Appenda chaves novas que não existiam no arquivo original
        resolved.forEach((k, v) -> {
            if (!written.contains(k)) {
                output.add(k + "=" + v);
            }
        });

        // Garante que o diretório pai existe
        Files.createDirectories(path.getParent());

        // Escreve atomicamente (write -> rename)
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, String.join(System.lineSeparator(), output) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        log.info("Arquivo .env atualizado em {} ({} chaves)", settingsFilePath, resolved.size());

        // Registra histórico de alterações (apenas chaves que mudaram)
        recordHistory(current, resolved, changedBy, ipAddress);
    }

    /**
     * Sobrecarga sem contexto de requisição (compatibilidade).
     */
    public void writeSettings(Map<String, String> updates) throws IOException {
        writeSettings(updates, "admin", "unknown");
    }

    // -------------------------------------------------------------------------
    // Histórico de alterações
    // -------------------------------------------------------------------------

    /**
     * Registra no banco todas as chaves cujo valor efetivamente mudou.
     * Campos secretos são gravados mascarados.
     */
    private void recordHistory(Map<String, String> current, Map<String, String> resolved,
                                String changedBy, String ipAddress) {
        List<SettingsHistory> records = new ArrayList<>();
        resolved.forEach((key, newVal) -> {
            String oldVal = current.get(key);
            // Compara valores; pula se for igual
            if (!Objects.equals(oldVal, newVal)) {
                boolean secret = SECRET_KEYS.contains(key);
                records.add(SettingsHistory.builder()
                        .changedAt(OffsetDateTime.now())
                        .changedBy(changedBy != null ? changedBy : "admin")
                        .envKey(key)
                        .oldValue(secret ? (oldVal != null ? MASK_DISPLAY : null) : oldVal)
                        .newValue(secret ? MASK_DISPLAY : newVal)
                        .ipAddress(ipAddress)
                        .build());
            }
        });

        if (!records.isEmpty()) {
            historyRepository.saveAll(records);
            log.info("Histórico: {} alteração(ões) registrada(s) por {}", records.size(), changedBy);
        }
    }

    /**
     * Retorna o histórico de alterações (mais recentes primeiro).
     */
    public List<SettingsHistory> getHistory(int limit) {
        return historyRepository.findAllByOrderByChangedAtDesc(
                PageRequest.of(0, Math.max(1, Math.min(limit, 200)))
        );
    }

    // -------------------------------------------------------------------------
    // Backup do .env
    // -------------------------------------------------------------------------

    /**
     * Cria uma cópia do .env atual com sufixo timestamp.
     * Mantém apenas os MAX_BACKUPS mais recentes no diretório.
     */
    public void backupEnv(Path envPath) {
        if (!Files.exists(envPath)) return;
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backup = envPath.resolveSibling(".env.bak-" + ts);
            Files.copy(envPath, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("Backup do .env criado: {}", backup.getFileName());
            pruneOldBackups(envPath.getParent());
        } catch (IOException e) {
            log.warn("Não foi possível criar backup do .env: {}", e.getMessage());
        }
    }

    /** Remove backups mais antigos se passar de MAX_BACKUPS. */
    private void pruneOldBackups(Path dir) {
        try {
            List<Path> backups;
            try (var stream = Files.list(dir)) {
                backups = stream
                        .filter(p -> p.getFileName().toString().startsWith(".env.bak-"))
                        .sorted(Comparator.reverseOrder())
                        .collect(Collectors.toList());
            }
            if (backups.size() > MAX_BACKUPS) {
                backups.subList(MAX_BACKUPS, backups.size())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
                log.info("Backups antigos removidos, mantendo os {} mais recentes", MAX_BACKUPS);
            }
        } catch (IOException e) {
            log.warn("Erro ao limpar backups antigos: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Apply interno (chamado pela Thread virtual)
    // -------------------------------------------------------------------------

    private void runApply(ApplyJob job, List<String> services) {
        log.info("Solicitando docker compose up -d ao docker-helper (jobId={})", job.getId());

        // Backup do .env antes de aplicar
        Path envPath = Path.of(settingsFilePath);
        backupEnv(envPath);

        if (services != null && !services.isEmpty()) {
            job.appendLog("Reiniciando apenas: " + String.join(", ", services) + "\n\n");
        } else {
            job.appendLog("Reiniciando todos os serviços\n\n");
        }

        Map<String, Object> response = webClientBuilder.build()
                .post()
                .uri(dockerHelperUrl + "/compose/up")
                .header("X-Internal-Key", internalApiKey)
                .bodyValue(Map.of("services", services != null ? services : List.of()))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMinutes(3))
                .block();

        String output = response != null ? String.valueOf(response.get("output")) : "";
        int exitCode  = response != null ? ((Number) response.get("exitCode")).intValue() : -1;

        job.appendLog(output);
        log.info("[docker-compose via docker-helper] {}", output);

        if (exitCode != 0) {
            log.error("docker compose up retornou exit code {}", exitCode);
            job.appendLog("\nExit code: " + exitCode);
            throw new RuntimeException("docker compose up retornou exit code " + exitCode);
        }
        log.info("docker compose up concluido com sucesso (jobId={})", job.getId());
        job.appendLog("\nServicos reiniciados com sucesso!");
    }

    // -------------------------------------------------------------------------
    // Privado
    // -------------------------------------------------------------------------

    /** Lê o .env como Map<key, value> sem mascarar nada (uso interno). */
    private Map<String, String> readCurrentRaw(Path path) throws IOException {
        if (!Files.exists(path)) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.trim().startsWith("#")) {
                map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return map;
    }
}
