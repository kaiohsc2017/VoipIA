package com.asteriskia.domain.settings;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * SettingsApplyJobService — orquestra o apply assíncrono (docker compose up via docker-helper),
 * extraído de SettingsService (fase 9 da refatoração). Cada apply roda em uma Thread virtual
 * própria; o estado é consultado por polling via getApplyStatus(jobId).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsApplyJobService {

    private final EnvFileStore envFileStore;
    private final WebClient.Builder webClientBuilder;

    /** URL do docker-helper — único container com acesso ao docker.sock (F-CRIT-10). */
    @Value("${app.docker-helper.url}")
    private String dockerHelperUrl;

    @Value("${app.internal-api-key}")
    private String internalApiKey;

    public enum JobStatus {
        RUNNING,
        DONE,
        ERROR
    }

    /** Estado de um job de apply em andamento ou concluído. */
    public static class ApplyJob {
        private final String id;
        private final Instant createdAt = Instant.now();
        private volatile JobStatus status = JobStatus.RUNNING;
        private final StringBuilder log = new StringBuilder();

        ApplyJob(String id) {
            this.id = id;
        }

        synchronized void appendLog(String line) {
            log.append(line);
        }

        void setStatus(JobStatus s) {
            this.status = s;
        }

        public String getId() {
            return id;
        }

        public JobStatus getStatus() {
            return status;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public synchronized String getLog() {
            return log.toString();
        }
    }

    /** Máximo de tempo que um job de apply concluído fica retido em memória. */
    private static final Duration JOB_RETENTION = Duration.ofHours(1);

    /**
     * Mapa de jobs ativos/concluídos — limpo oportunisticamente a cada novo apply (ver
     * startApplyAsync).
     */
    private final ConcurrentHashMap<String, ApplyJob> jobs = new ConcurrentHashMap<>();

    /** Reinicia apenas os serviços listados (lista vazia = todos). */
    public String startApplyAsync(List<String> services) {
        purgeOldJobs();

        String jobId = UUID.randomUUID().toString();
        ApplyJob job = new ApplyJob(jobId);
        jobs.put(jobId, job);
        log.info("Apply iniciado assincronamente, jobId={}", jobId);

        Thread.ofVirtual()
                .name("apply-" + jobId)
                .start(
                        () -> {
                            try {
                                runApply(job, services);
                                job.setStatus(JobStatus.DONE);
                            } catch (Exception e) {
                                log.error(
                                        "Erro no apply assíncrono jobId={}: {}",
                                        jobId,
                                        e.getMessage(),
                                        e);
                                job.appendLog("\n❌ Erro: " + e.getMessage());
                                job.setStatus(JobStatus.ERROR);
                            }
                        });

        return jobId;
    }

    /** Retorna o estado atual de um job de apply. */
    public Optional<ApplyJob> getApplyStatus(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * Remove do mapa jobs criados há mais de JOB_RETENTION. Cada job guarda um StringBuilder com o
     * log completo do docker compose up — sem essa limpeza, o mapa cresceria indefinidamente ao
     * longo da vida do processo.
     */
    private void purgeOldJobs() {
        Instant threshold = Instant.now().minus(JOB_RETENTION);
        jobs.values().removeIf(job -> job.getCreatedAt().isBefore(threshold));
    }

    private void runApply(ApplyJob job, List<String> services) {
        log.info("Solicitando docker compose up -d ao docker-helper (jobId={})", job.getId());

        // Backup do .env antes de aplicar
        envFileStore.backup();

        if (services != null && !services.isEmpty()) {
            job.appendLog("Reiniciando apenas: " + String.join(", ", services) + "\n\n");
        } else {
            job.appendLog("Reiniciando todos os serviços\n\n");
        }

        Map<String, Object> response =
                webClientBuilder
                        .build()
                        .post()
                        .uri(dockerHelperUrl + "/compose/up")
                        .header("X-Internal-Key", internalApiKey)
                        .bodyValue(Map.of("services", services != null ? services : List.of()))
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(Duration.ofMinutes(3))
                        .block();

        String output = response != null ? String.valueOf(response.get("output")) : "";
        int exitCode = response != null ? ((Number) response.get("exitCode")).intValue() : -1;

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
}
