package com.asteriskia.domain.logs;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
public class LogsController {

    private final AuditService auditService;
    private final DockerHelperClient dockerHelperClient;
    private final AsteriskAmiClient asteriskAmiClient;

    private static final int SSE_TIMEOUT = (int) TimeUnit.MINUTES.toMillis(30);

    // Containers reais do stack atual (deve casar com _ALLOWED_SERVICES do docker-helper).
    private static final List<String> ALL_SERVICES =
            List.of(
                    "voipia-backend",
                    "voipia-asterisk",
                    "voipia-ai-agent",
                    "voipia-frontend",
                    "voipia-postgres",
                    "voipia-agents-api",
                    "voipia-caddy");

    // ── Docker snapshot ───────────────────────────────────────────────────────

    @GetMapping("/docker")
    public ResponseEntity<Map<String, Object>> dockerSnapshot(
            @RequestParam(defaultValue = "") String services,
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(defaultValue = "") String levels) {
        try {
            List<String> svcs = resolveServices(services);
            List<Map<String, String>> entries = new ArrayList<>();
            for (String svc : svcs) {
                for (String line : dockerHelperClient.runDockerLogs(svc, lines, null, null))
                    if (LogLineParser.matchesLevel(line, levels))
                        entries.add(LogLineParser.parseLine(svc, line));
            }
            entries.sort(Comparator.comparing(e -> e.getOrDefault("ts", "")));
            return ResponseEntity.ok(Map.of("entries", entries, "total", entries.size()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Docker histórico ──────────────────────────────────────────────────────

    @GetMapping("/docker/history")
    public ResponseEntity<Map<String, Object>> dockerHistory(
            @RequestParam(defaultValue = "") String services,
            @RequestParam(defaultValue = "500") int lines,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String levels) {
        try {
            List<String> svcs = resolveServices(services);
            String since = from.isBlank() ? null : from + "T00:00:00";
            String until = to.isBlank() ? null : to + "T23:59:59";
            List<Map<String, String>> entries = new ArrayList<>();
            for (String svc : svcs)
                for (String line : dockerHelperClient.runDockerLogs(svc, lines, since, until))
                    if (LogLineParser.matchesLevel(line, levels))
                        entries.add(LogLineParser.parseLine(svc, line));
            entries.sort(Comparator.comparing(e -> e.getOrDefault("ts", "")));
            return ResponseEntity.ok(
                    Map.of(
                            "entries",
                            entries,
                            "total",
                            entries.size(),
                            "chart",
                            LogLineParser.buildHourChart(
                                    entries, "level", Set.of("ERROR", "WARN"))));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Docker SSE ────────────────────────────────────────────────────────────

    @GetMapping(value = "/docker/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter dockerStream(
            @RequestParam(defaultValue = "") String services,
            @RequestParam(defaultValue = "") String levels) {

        List<String> svcs = resolveServices(services);
        SseEmitter emitter = new SseEmitter((long) SSE_TIMEOUT);

        Thread.ofVirtual()
                .name("log-stream-docker")
                .start(
                        () -> {
                            List<Stream<String>> streams =
                                    Collections.synchronizedList(new ArrayList<>());
                            BlockingQueue<String> queue = new LinkedBlockingQueue<>(2000);
                            try {
                                for (String svc : svcs) {
                                    final String fsvc = svc;
                                    Thread.ofVirtual()
                                            .start(
                                                    () -> {
                                                        try {
                                                            Stream<String> lines =
                                                                    dockerHelperClient
                                                                            .streamFromHelper(
                                                                                    "/logs/"
                                                                                            + fsvc
                                                                                            + "/stream?tail=50");
                                                            streams.add(lines);
                                                            lines.forEach(
                                                                    line -> {
                                                                        try {
                                                                            queue.offer(
                                                                                    fsvc + "|||"
                                                                                            + line,
                                                                                    1,
                                                                                    TimeUnit
                                                                                            .SECONDS);
                                                                        } catch (
                                                                                InterruptedException
                                                                                        ie) {
                                                                            Thread.currentThread()
                                                                                    .interrupt();
                                                                        }
                                                                    });
                                                        } catch (Exception ex) {
                                                            log.debug(
                                                                    "Stream de log encerrado para o serviço '{}': {}",
                                                                    fsvc,
                                                                    ex.getMessage());
                                                        }
                                                    });
                                }
                                while (!Thread.currentThread().isInterrupted()) {
                                    String raw = queue.poll(5, TimeUnit.SECONDS);
                                    if (raw == null) {
                                        emitter.send(SseEmitter.event().comment("ping"));
                                        continue;
                                    }
                                    String[] parts = raw.split("\\|\\|\\|", 2);
                                    String line = parts.length > 1 ? parts[1] : "";
                                    if (!LogLineParser.matchesLevel(line, levels)) continue;
                                    emitter.send(
                                            SseEmitter.event()
                                                    .data(
                                                            LogLineParser.toJson(
                                                                    LogLineParser.parseLine(
                                                                            parts[0], line))));
                                }
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            } finally {
                                streams.forEach(Stream::close);
                            }
                        });

        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    // ── Docker download ───────────────────────────────────────────────────────

    @GetMapping("/docker/download")
    public ResponseEntity<byte[]> dockerDownload(
            @RequestParam(defaultValue = "") String services,
            @RequestParam(defaultValue = "1000") int lines,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            HttpServletRequest request) {
        try {
            List<String> svcs = resolveServices(services);
            String since = from.isBlank() ? null : from + "T00:00:00";
            String until = to.isBlank() ? null : to + "T23:59:59";
            StringBuilder sb =
                    new StringBuilder("# VoipIA — Docker Logs\n# " + Instant.now() + "\n\n");
            for (String svc : svcs) {
                sb.append("=== ").append(svc).append(" ===\n");
                dockerHelperClient
                        .runDockerLogs(svc, lines, since, until)
                        .forEach(l -> sb.append(l).append("\n"));
                sb.append("\n");
            }
            String ts =
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.now());
            auditService.log(request, "LOGS_DOWNLOAD", "Docker: " + svcs, true);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=docker-" + ts + ".log")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Asterisk status (AMI) ─────────────────────────────────────────────────

    @GetMapping("/asterisk/status")
    public ResponseEntity<Map<String, Object>> asteriskStatus() {
        return ResponseEntity.ok(asteriskAmiClient.fetchStatus());
    }

    // ── Asterisk snapshot ─────────────────────────────────────────────────────

    @GetMapping("/asterisk")
    public ResponseEntity<Map<String, Object>> asteriskSnapshot(
            @RequestParam(defaultValue = "300") int lines,
            @RequestParam(defaultValue = "") String levels) {
        try {
            List<Map<String, String>> entries = new ArrayList<>();
            for (String line : dockerHelperClient.tailAsteriskLog(lines)) {
                Map<String, String> e = LogLineParser.parseAsteriskLine(line);
                if (LogLineParser.matchesAsteriskLevel(e.get("category"), levels)) entries.add(e);
            }
            return ResponseEntity.ok(
                    Map.of(
                            "entries",
                            entries,
                            "total",
                            entries.size(),
                            "chart",
                            LogLineParser.buildAsteriskChart(entries)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Asterisk SSE ──────────────────────────────────────────────────────────

    @GetMapping(value = "/asterisk/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter asteriskStream(@RequestParam(defaultValue = "") String levels) {
        SseEmitter emitter = new SseEmitter((long) SSE_TIMEOUT);
        Thread.ofVirtual()
                .name("log-stream-asterisk")
                .start(
                        () -> {
                            Stream<String> lines = null;
                            try {
                                lines =
                                        dockerHelperClient.streamFromHelper(
                                                "/asterisk/log/stream?lines=50");
                                Iterator<String> it = lines.iterator();
                                while (it.hasNext()) {
                                    Map<String, String> e =
                                            LogLineParser.parseAsteriskLine(it.next());
                                    if (!LogLineParser.matchesAsteriskLevel(
                                            e.get("category"), levels)) continue;
                                    emitter.send(SseEmitter.event().data(LogLineParser.toJson(e)));
                                }
                            } catch (Exception e) {
                                emitter.complete();
                            } finally {
                                if (lines != null) lines.close();
                            }
                        });
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    // ── Asterisk download ─────────────────────────────────────────────────────

    @GetMapping("/asterisk/download")
    public ResponseEntity<byte[]> asteriskDownload(
            @RequestParam(defaultValue = "2000") int lines, HttpServletRequest request) {
        try {
            List<String> raw = dockerHelperClient.tailAsteriskLog(lines);
            String ts =
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.now());
            StringBuilder sb =
                    new StringBuilder("# VoipIA — Asterisk Log\n# " + Instant.now() + "\n\n");
            raw.forEach(l -> sb.append(l).append("\n"));
            auditService.log(request, "LOGS_DOWNLOAD", "Asterisk log", true);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=asterisk-" + ts + ".log")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<String> resolveServices(String param) {
        if (param == null || param.isBlank()) return ALL_SERVICES;
        List<String> r = new ArrayList<>();
        for (String s : param.split(",")) {
            String t = s.trim();
            if (t.startsWith("voipia-")) {
                r.add(t);
            } else if (t.startsWith("asteriskia-")) {
                r.add(t.replace("asteriskia-", "voipia-"));
            } else {
                r.add("voipia-" + t);
            }
        }
        return r;
    }
}
