package com.asteriskia.domain.settings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SettingsService — Leitura, escrita e aplicação do arquivo .env do sistema.
 *
 * O arquivo .env fica em SETTINGS_FILE_PATH (montado via volume Docker).
 * O diretório de compose fica em COMPOSE_DIR (também montado).
 *
 * Campos secretos são mascarados no GET e preservados no PUT caso o
 * cliente envie o valor mascarado ("••••••••").
 *
 * O apply agora é ASSÍNCRONO:
 *   1. startApplyAsync()    → lança Thread virtual, retorna jobId (UUID)
 *   2. getApplyStatus(jobId) → retorna estado + log acumulado
 * Isso evita timeout HTTP em rebuilds longos (ex: backend com Maven ~2 min).
 */
@Slf4j
@Service
public class SettingsService {

    /** Caminho do .env dentro do container (mapeado via volume). */
    @Value("${app.settings.file-path:/opt/asteriskia/env/.env}")
    private String settingsFilePath;

    /** Diretório raiz do projeto Docker Compose no host (mapeado via volume). */
    @Value("${app.settings.compose-dir:/opt/AsteriskIA}")
    private String composeDir;

    /** Token sentinela enviado pelo frontend quando o campo não foi alterado. */
    private static final String MASK_SENTINEL = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
    private static final String MASK_DISPLAY  = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";

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
     * Retorna o jobId imediatamente — o chamador deve poluir GET /apply/{jobId}/status.
     */
    public String startApplyAsync() {
        String jobId = UUID.randomUUID().toString();
        ApplyJob job = new ApplyJob(jobId);
        jobs.put(jobId, job);
        log.info("Apply iniciado assincronamente, jobId={}", jobId);

        Thread.ofVirtual().name("apply-" + jobId).start(() -> {
            try {
                runApply(job);
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
     * Retorna Optional.empty() se o jobId não existir.
     */
    public Optional<ApplyJob> getApplyStatus(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    // -------------------------------------------------------------------------
    // Leitura
    // -------------------------------------------------------------------------

    /**
     * Lê o arquivo .env e retorna uma lista ordenada de entradas.
     * Linhas de comentário e linhas em branco são incluídas com type=COMMENT/BLANK.
     * Campos secretos são retornados mascarados.
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
     *
     * @param updates  Map de key -> novo valor. Se o valor for o sentinela de
     *                 máscara, o valor atual é preservado.
     */
    public void writeSettings(Map<String, String> updates) throws IOException {
        Path path = Path.of(settingsFilePath);

        // Lê o estado atual para preservar valores mascarados que não foram alterados
        Map<String, String> current = readCurrentRaw(path);

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
    }

    // -------------------------------------------------------------------------
    // Apply interno (chamado pela Thread virtual)
    // -------------------------------------------------------------------------

    private void runApply(ApplyJob job) throws IOException, InterruptedException {
        log.info("Executando docker compose up -d em {} (jobId={})", composeDir, job.getId());
        job.appendLog("\u25b6 Executando: docker compose up -d --remove-orphans\n");
        job.appendLog("\ud83d\udcc1 Diret\u00f3rio: " + composeDir + "\n\n");

        ProcessBuilder pb = new ProcessBuilder(
                "docker", "compose",
                "--env-file", settingsFilePath,
                "up", "-d", "--remove-orphans"
        );
        pb.directory(new File(composeDir));
        pb.redirectErrorStream(true); // stderr -> stdout

        Process proc = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                job.appendLog(line + "\n");
                log.info("[docker-compose] {}", line);
            }
        }

        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            log.error("docker compose up retornou exit code {}", exitCode);
            job.appendLog("\n\u26a0\ufe0f  Exit code: " + exitCode);
            throw new RuntimeException("docker compose up retornou exit code " + exitCode);
        }
        log.info("docker compose up concluido com sucesso (jobId={})", job.getId());
        job.appendLog("\n\u2705 Servicos reiniciados com sucesso!");
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
