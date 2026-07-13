package com.asteriskia.domain.logs;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
public class LogsController {

    private final AuditService auditService;
    private final RestTemplate restTemplate;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Docker Helper — único container com acesso ao docker.sock (F-CRIT-10).
    // Este controller não roda mais 'docker logs'/'docker exec' via ProcessBuilder.
    @Value("${app.docker-helper.url}")
    private String dockerHelperUrl;

    @Value("${app.internal-api-key}")
    private String internalApiKey;

    @Value("${app.asterisk.ami.host:asterisk}")
    private String amiHost;

    @Value("${app.asterisk.ami.port:5038}")
    private int amiPort;

    @Value("${app.asterisk.ami.user:asteriskia}")
    private String amiUser;

    @Value("${app.asterisk.ami.password}")
    private String amiPassword;

    private static final int AMI_TIMEOUT = 8_000;
    private static final int SSE_TIMEOUT = (int) TimeUnit.MINUTES.toMillis(30);

    // Containers reais do stack atual (deve casar com _ALLOWED_SERVICES do docker-helper).
    private static final List<String> ALL_SERVICES =
            List.of(
                    "asteriskia-backend",
                    "asteriskia-asterisk",
                    "asteriskia-ai-agent",
                    "asteriskia-frontend",
                    "asteriskia-postgres",
                    "asteriskia-agents-api",
                    "asteriskia-caddy");

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
                for (String line : runDockerLogs(svc, lines, null, null))
                    if (matchesLevel(line, levels)) entries.add(parseLine(svc, line));
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
                for (String line : runDockerLogs(svc, lines, since, until))
                    if (matchesLevel(line, levels)) entries.add(parseLine(svc, line));
            entries.sort(Comparator.comparing(e -> e.getOrDefault("ts", "")));
            return ResponseEntity.ok(
                    Map.of(
                            "entries",
                            entries,
                            "total",
                            entries.size(),
                            "chart",
                            buildHourChart(entries, "level", Set.of("ERROR", "WARN"))));
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
                                                                    streamFromHelper(
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
                                    if (!matchesLevel(line, levels)) continue;
                                    emitter.send(
                                            SseEmitter.event()
                                                    .data(toJson(parseLine(parts[0], line))));
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
                    new StringBuilder("# AsteriskIA — Docker Logs\n# " + Instant.now() + "\n\n");
            for (String svc : svcs) {
                sb.append("=== ").append(svc).append(" ===\n");
                runDockerLogs(svc, lines, since, until).forEach(l -> sb.append(l).append("\n"));
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
            if (!readBlock(r).contains("Success"))
                return ResponseEntity.ok(Map.of("ok", false, "error", "ami_auth"));

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
        return ResponseEntity.ok(result);
    }

    // ── Asterisk snapshot ─────────────────────────────────────────────────────

    @GetMapping("/asterisk")
    public ResponseEntity<Map<String, Object>> asteriskSnapshot(
            @RequestParam(defaultValue = "300") int lines,
            @RequestParam(defaultValue = "") String levels) {
        try {
            List<Map<String, String>> entries = new ArrayList<>();
            for (String line : tailAsteriskLog(lines)) {
                Map<String, String> e = parseAsteriskLine(line);
                if (matchesAsteriskLevel(e.get("category"), levels)) entries.add(e);
            }
            return ResponseEntity.ok(
                    Map.of(
                            "entries",
                            entries,
                            "total",
                            entries.size(),
                            "chart",
                            buildAsteriskChart(entries)));
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
                                lines = streamFromHelper("/asterisk/log/stream?lines=50");
                                Iterator<String> it = lines.iterator();
                                while (it.hasNext()) {
                                    Map<String, String> e = parseAsteriskLine(it.next());
                                    if (!matchesAsteriskLevel(e.get("category"), levels)) continue;
                                    emitter.send(SseEmitter.event().data(toJson(e)));
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
            List<String> raw = tailAsteriskLog(lines);
            String ts =
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.now());
            StringBuilder sb =
                    new StringBuilder("# AsteriskIA — Asterisk Log\n# " + Instant.now() + "\n\n");
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

    /**
     * Chama o docker-helper (GET /logs/{svc}) — antigo ProcessBuilder("docker","logs",...). Falha
     * de UM serviço (ex: nome inválido) não derruba a consulta dos demais.
     */
    @SuppressWarnings("unchecked")
    private List<String> runDockerLogs(String svc, int lines, String since, String until) {
        try {
            UriComponentsBuilder b =
                    UriComponentsBuilder.fromHttpUrl(dockerHelperUrl + "/logs/" + svc)
                            .queryParam("tail", lines);
            if (since != null) b.queryParam("since", since);
            if (until != null) b.queryParam("until", until);
            Map<String, Object> body = callHelper(b.toUriString());
            return body != null ? (List<String>) body.getOrDefault("lines", List.of()) : List.of();
        } catch (Exception e) {
            log.warn("runDockerLogs({}): {}", svc, e.getMessage());
            return List.of();
        }
    }

    /** Chama o docker-helper (GET /asterisk/log) — antigo docker exec asteriskia-asterisk tail. */
    @SuppressWarnings("unchecked")
    private List<String> tailAsteriskLog(int lines) {
        String url =
                UriComponentsBuilder.fromHttpUrl(dockerHelperUrl + "/asterisk/log")
                        .queryParam("lines", lines)
                        .toUriString();
        Map<String, Object> body = callHelper(url);
        return body != null ? (List<String>) body.getOrDefault("lines", List.of()) : List.of();
    }

    private Map<String, Object> callHelper(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalApiKey);
        ResponseEntity<Map> resp =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        return resp.getBody();
    }

    /** Consome um endpoint de streaming (text/plain, linha por linha) do docker-helper. */
    private Stream<String> streamFromHelper(String path) throws IOException, InterruptedException {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(dockerHelperUrl + path))
                        .header("X-Internal-Key", internalApiKey)
                        .GET()
                        .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofLines()).body();
    }

    private List<String> resolveServices(String param) {
        if (param == null || param.isBlank()) return ALL_SERVICES;
        List<String> r = new ArrayList<>();
        for (String s : param.split(",")) {
            String t = s.trim();
            r.add(t.startsWith("asteriskia-") ? t : "asteriskia-" + t);
        }
        return r;
    }

    private Map<String, String> parseLine(String svc, String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("service", svc.replace("asteriskia-", ""));
        if (raw.length() > 31 && raw.charAt(10) == 'T') {
            m.put("ts", raw.substring(0, 19).replace("T", " "));
            m.put("msg", raw.substring(31).trim());
        } else {
            m.put("ts", "");
            m.put("msg", raw);
        }
        m.put("level", detectDockerLevel(m.get("msg")));
        return m;
    }

    private String detectDockerLevel(String msg) {
        if (msg == null) return "INFO";
        String u = msg.toUpperCase();
        if (u.contains("ERROR") || u.contains("EXCEPTION")) return "ERROR";
        if (u.contains("WARN")) return "WARN";
        if (u.contains("DEBUG")) return "DEBUG";
        return "INFO";
    }

    private boolean matchesLevel(String line, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String u = line.toUpperCase();
        for (String f : filter.toUpperCase().split(",")) if (u.contains(f.trim())) return true;
        return false;
    }

    private Map<String, String> parseAsteriskLine(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("raw", raw);
        if (raw.startsWith("[") && raw.length() > 20) {
            int close = raw.indexOf(']');
            if (close > 0) {
                m.put("ts", raw.substring(1, close));
                String rest = raw.substring(close + 1).trim();
                String level = "INFO";
                if (rest.startsWith("WARNING")) level = "WARNING";
                else if (rest.startsWith("ERROR")) level = "ERROR";
                else if (rest.startsWith("NOTICE")) level = "NOTICE";
                else if (rest.startsWith("VERBOSE")) level = "VERBOSE";
                else if (rest.startsWith("DEBUG")) level = "DEBUG";
                m.put("level", level);
                m.put("category", detectAsteriskCategory(rest));
                int b = rest.indexOf('['), c = rest.indexOf(']', b > 0 ? b : 0);
                if (b >= 0 && c > b) {
                    String ap = rest.substring(c + 1).trim();
                    int col = ap.indexOf(':');
                    m.put("msg", col >= 0 ? ap.substring(col + 1).trim() : ap);
                } else m.put("msg", rest);
            }
        } else {
            m.put("ts", "");
            m.put("level", "INFO");
            m.put("category", detectAsteriskCategory(raw));
            m.put("msg", raw);
        }
        return m;
    }

    private String detectAsteriskCategory(String line) {
        if (line == null) return "INFO";
        String u = line.toUpperCase();
        if (u.contains("REGISTER")) return "REGISTER";
        if (u.contains("DTLS") || u.contains("SRTP") || u.contains("ICE")) return "DTLS";
        if (u.contains("PJSIP") || u.contains("RES_PJSIP")) return "PJSIP";
        if (u.contains("DIAL")
                || u.contains("HANGUP")
                || u.contains("ANSWER")
                || u.contains("BRIDGE")) return "CALL";
        if (u.contains("AMI")) return "AMI";
        if (u.contains("ERROR")) return "ERROR";
        if (u.contains("WARNING") || u.contains("WARN")) return "WARN";
        return "INFO";
    }

    private boolean matchesAsteriskLevel(String cat, String filter) {
        if (filter == null || filter.isBlank() || cat == null) return true;
        for (String f : filter.toUpperCase().split(","))
            if (cat.toUpperCase().contains(f.trim())) return true;
        return false;
    }

    private Map<String, Object> buildHourChart(
            List<Map<String, String>> entries, String levelKey, Set<String> errLevels) {
        Map<String, Integer> byH = new LinkedHashMap<>(), errH = new LinkedHashMap<>();
        for (var e : entries) {
            String ts = e.getOrDefault("ts", "");
            String h = ts.length() >= 13 ? ts.substring(11, 13) + "h" : "?h";
            byH.merge(h, 1, Integer::sum);
            if (errLevels.contains(e.getOrDefault(levelKey, ""))) errH.merge(h, 1, Integer::sum);
        }
        return Map.of("byHour", byH, "errByHour", errH);
    }

    private Map<String, Object> buildAsteriskChart(List<Map<String, String>> entries) {
        Map<String, Integer> byH = new LinkedHashMap<>(), errH = new LinkedHashMap<>();
        for (var e : entries) {
            String ts = e.getOrDefault("ts", "");
            String h = ts.length() >= 8 ? ts.substring(7, 9) + "h" : "?h";
            byH.merge(h, 1, Integer::sum);
            String cat = e.getOrDefault("category", "");
            if (cat.equals("ERROR") || cat.equals("WARN") || cat.equals("DTLS"))
                errH.merge(h, 1, Integer::sum);
        }
        return Map.of("byHour", byH, "errByHour", errH);
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

    private String toJson(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach(
                (k, v) ->
                        sb.append("\"")
                                .append(k)
                                .append("\":\"")
                                .append(
                                        v == null
                                                ? ""
                                                : v.replace("\\", "\\\\")
                                                        .replace("\"", "\\\"")
                                                        .replace("\n", "\\n"))
                                .append("\","));
        if (sb.charAt(sb.length() - 1) == ',') sb.setCharAt(sb.length() - 1, '}');
        else sb.append('}');
        return sb.toString();
    }
}
