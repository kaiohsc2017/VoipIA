package com.asteriskia.domain.logs;

import com.asteriskia.domain.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
@Tag(name = "Logs", description = "Logs Docker e Asterisk — snapshot, stream SSE e download")
public class LogsController {

    private final AuditService auditService;

    @Value("${app.settings.compose-dir:/opt/AsteriskIA}")
    private String composeDir;

    @Value("${app.asterisk.ami.host:asterisk}")
    private String amiHost;

    @Value("${app.asterisk.ami.port:5038}")
    private int amiPort;

    @Value("${app.asterisk.ami.user:asteriskia}")
    private String amiUser;

    @Value("${app.asterisk.ami.password:asteriskia_ami_pass}")
    private String amiPassword;

    private static final int AMI_TIMEOUT = 8_000;
    private static final int SSE_TIMEOUT = (int) TimeUnit.MINUTES.toMillis(30);

    private static final List<String> ALL_SERVICES = List.of(
        "asteriskia-backend", "asteriskia-asterisk", "asteriskia-ai-agent",
        "asteriskia-scheduler", "asteriskia-frontend", "asteriskia-postgres",
        "asteriskia-prometheus", "asteriskia-grafana"
    );

    // ── Docker snapshot ───────────────────────────────────────────────────────

    @GetMapping("/docker")
    @Operation(summary = "Últimas N linhas dos containers selecionados")
    public ResponseEntity<Map<String, Object>> dockerSnapshot(
            @RequestParam(defaultValue = "") String services,
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(defaultValue = "") String levels) {
        try {
            List<String> svcs = resolveServices(services);
            List<Map<String,String>> entries = new ArrayList<>();
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
    @Operation(summary = "Logs de um período (from/to: yyyy-MM-dd)")
    public ResponseEntity<Map<String, Object>> dockerHistory(
            @RequestParam(defaultValue = "") String services,
            @RequestParam(defaultValue = "500") int lines,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String levels) {
        try {
            List<String> svcs   = resolveServices(services);
            String since        = from.isBlank() ? null : from + "T00:00:00";
            String until        = to.isBlank()   ? null : to   + "T23:59:59";
            List<Map<String,String>> entries = new ArrayList<>();
            for (String svc : svcs)
                for (String line : runDockerLogs(svc, lines, since, until))
                    if (matchesLevel(line, levels)) entries.add(parseLine(svc, line));
            entries.sort(Comparator.comparing(e -> e.getOrDefault("ts", "")));
            return ResponseEntity.ok(Map.of(
                "entries", entries, "total", entries.size(),
                "chart",   buildHourChart(entries, "level", Set.of("ERROR","WARN"))));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Docker SSE ────────────────────────────────────────────────────────────

    @GetMapping(value = "/docker/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream SSE de logs Docker em tempo real")
    public SseEmitter dockerStream(
            @RequestParam(defaultValue = "") String services,
            @RequestParam(defaultValue = "") String levels) {

        List<String> svcs   = resolveServices(services);
        SseEmitter emitter  = new SseEmitter((long) SSE_TIMEOUT);

        Thread.ofVirtual().name("log-stream-docker").start(() -> {
            List<Process> procs = new ArrayList<>();
            BlockingQueue<String> queue = new LinkedBlockingQueue<>(2000);
            try {
                for (String svc : svcs) {
                    ProcessBuilder pb = new ProcessBuilder(
                        "docker","logs","--follow","--tail","50","--timestamps", svc);
                    pb.redirectErrorStream(true);
                    Process p = pb.start(); procs.add(p);
                    final String fsvc = svc;
                    final InputStream is = p.getInputStream();
                    Thread.ofVirtual().start(() -> {
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = r.readLine()) != null)
                                queue.offer(fsvc + "|||" + line, 1, TimeUnit.SECONDS);
                        } catch (Exception ignored) {}
                    });
                }
                while (!Thread.currentThread().isInterrupted()) {
                    String raw = queue.poll(5, TimeUnit.SECONDS);
                    if (raw == null) { emitter.send(SseEmitter.event().comment("ping")); continue; }
                    String[] parts = raw.split("\\|\\|\\|", 2);
                    String line = parts.length > 1 ? parts[1] : "";
                    if (!matchesLevel(line, levels)) continue;
                    emitter.send(SseEmitter.event().data(toJson(parseLine(parts[0], line))));
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                procs.forEach(Process::destroyForcibly);
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
            String until = to.isBlank()   ? null : to   + "T23:59:59";
            StringBuilder sb = new StringBuilder("# AsteriskIA — Docker Logs\n# " + Instant.now() + "\n\n");
            for (String svc : svcs) {
                sb.append("=== ").append(svc).append(" ===\n");
                runDockerLogs(svc, lines, since, until).forEach(l -> sb.append(l).append("\n"));
                sb.append("\n");
            }
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
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
    @Operation(summary = "Status ao vivo do Asterisk via AMI")
    public ResponseEntity<Map<String, Object>> asteriskStatus() {
        Map<String,Object> result = new LinkedHashMap<>();
        try (Socket s = new Socket(amiHost, amiPort)) {
            s.setSoTimeout(AMI_TIMEOUT);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter    w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            r.readLine();
            sendAmi(w, mapOf("Action","Login","Username",amiUser,"Secret",amiPassword));
            if (!readBlock(r).contains("Success")) return ResponseEntity.ok(Map.of("ok",false,"error","ami_auth"));

            sendAmi(w, mapOf("Action","Command","Command","core show uptime"));
            String uptime = readBlock(r);
            sendAmi(w, mapOf("Action","Command","Command","core show channels count"));
            String channels = readBlock(r);
            sendAmi(w, mapOf("Action","Command","Command","core show version"));
            String version = readBlock(r);
            sendAmi(w, mapOf("Action","Command","Command","pjsip show endpoints"));
            String endpoints = readBlock(r);
            sendAmi(w, mapOf("Action","Command","Command","pjsip show registrations"));
            String regs = readBlock(r);
            sendAmi(w, mapOf("Action","Logoff"));

            result.put("ok",        true);
            result.put("uptime",    extractValue(uptime, "System uptime:"));
            result.put("version",   extractFirstLine(version));
            result.put("channels",  extractChannelCount(channels));
            result.put("endpoints", parseEndpoints(endpoints));
            result.put("trunk",     parseTrunk(regs));
        } catch (Exception e) {
            log.warn("AMI status: {}", e.getMessage());
            result.put("ok", false); result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    // ── Asterisk snapshot ─────────────────────────────────────────────────────

    @GetMapping("/asterisk")
    @Operation(summary = "Últimas N linhas do log do Asterisk")
    public ResponseEntity<Map<String, Object>> asteriskSnapshot(
            @RequestParam(defaultValue = "300") int lines,
            @RequestParam(defaultValue = "") String levels) {
        try {
            List<Map<String,String>> entries = new ArrayList<>();
            for (String line : tailAsteriskLog(lines)) {
                Map<String,String> e = parseAsteriskLine(line);
                if (matchesAsteriskLevel(e.get("category"), levels)) entries.add(e);
            }
            return ResponseEntity.ok(Map.of(
                "entries", entries, "total", entries.size(),
                "chart",   buildAsteriskChart(entries)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Asterisk SSE ──────────────────────────────────────────────────────────

    @GetMapping(value = "/asterisk/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream SSE do log do Asterisk em tempo real")
    public SseEmitter asteriskStream(@RequestParam(defaultValue = "") String levels) {
        SseEmitter emitter = new SseEmitter((long) SSE_TIMEOUT);
        Thread.ofVirtual().name("log-stream-asterisk").start(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "docker","exec","asteriskia-asterisk",
                    "tail","-F","-n","50","/var/log/asterisk/full");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Map<String,String> e = parseAsteriskLine(line);
                        if (!matchesAsteriskLevel(e.get("category"), levels)) continue;
                        emitter.send(SseEmitter.event().data(toJson(e)));
                    }
                } finally { proc.destroyForcibly(); }
            } catch (Exception e) { emitter.complete(); }
        });
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    // ── Asterisk download ─────────────────────────────────────────────────────

    @GetMapping("/asterisk/download")
    public ResponseEntity<byte[]> asteriskDownload(
            @RequestParam(defaultValue = "2000") int lines,
            HttpServletRequest request) {
        try {
            List<String> raw = tailAsteriskLog(lines);
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
            StringBuilder sb = new StringBuilder("# AsteriskIA — Asterisk Log\n# " + Instant.now() + "\n\n");
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

    private List<String> runDockerLogs(String svc, int lines, String since, String until)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("docker","logs","--timestamps","--tail",String.valueOf(lines)));
        if (since != null) { cmd.add("--since"); cmd.add(since); }
        if (until != null) { cmd.add("--until"); cmd.add(until); }
        cmd.add(svc);
        ProcessBuilder pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true);
        Process p = pb.start();
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) out.add(l);
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return out;
    }

    private List<String> tailAsteriskLog(int lines) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "docker","exec","asteriskia-asterisk","tail","-n",String.valueOf(lines),"/var/log/asterisk/full");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) out.add(l);
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return out;
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

    private Map<String,String> parseLine(String svc, String raw) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("service", svc.replace("asteriskia-",""));
        if (raw.length() > 31 && raw.charAt(10)=='T') {
            m.put("ts",  raw.substring(0,19).replace("T"," "));
            m.put("msg", raw.substring(31).trim());
        } else { m.put("ts",""); m.put("msg",raw); }
        m.put("level", detectDockerLevel(m.get("msg")));
        return m;
    }

    private String detectDockerLevel(String msg) {
        if (msg == null) return "INFO";
        String u = msg.toUpperCase();
        if (u.contains("ERROR")||u.contains("EXCEPTION")) return "ERROR";
        if (u.contains("WARN")) return "WARN";
        if (u.contains("DEBUG")) return "DEBUG";
        return "INFO";
    }

    private boolean matchesLevel(String line, String filter) {
        if (filter==null||filter.isBlank()) return true;
        String u = line.toUpperCase();
        for (String f : filter.toUpperCase().split(",")) if (u.contains(f.trim())) return true;
        return false;
    }

    private Map<String,String> parseAsteriskLine(String raw) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("raw", raw);
        if (raw.startsWith("[") && raw.length()>20) {
            int close = raw.indexOf(']');
            if (close>0) {
                m.put("ts", raw.substring(1,close));
                String rest = raw.substring(close+1).trim();
                String level = "INFO";
                if (rest.startsWith("WARNING"))      level="WARNING";
                else if (rest.startsWith("ERROR"))   level="ERROR";
                else if (rest.startsWith("NOTICE"))  level="NOTICE";
                else if (rest.startsWith("VERBOSE")) level="VERBOSE";
                else if (rest.startsWith("DEBUG"))   level="DEBUG";
                m.put("level",    level);
                m.put("category", detectAsteriskCategory(rest));
                int b = rest.indexOf('['), c = rest.indexOf(']', b>0?b:0);
                if (b>=0&&c>b) {
                    String ap = rest.substring(c+1).trim();
                    int col = ap.indexOf(':');
                    m.put("msg", col>=0 ? ap.substring(col+1).trim() : ap);
                } else m.put("msg", rest);
            }
        } else {
            m.put("ts",""); m.put("level","INFO");
            m.put("category", detectAsteriskCategory(raw)); m.put("msg",raw);
        }
        return m;
    }

    private String detectAsteriskCategory(String line) {
        if (line==null) return "INFO";
        String u = line.toUpperCase();
        if (u.contains("REGISTER"))  return "REGISTER";
        if (u.contains("DTLS")||u.contains("SRTP")||u.contains("ICE")) return "DTLS";
        if (u.contains("PJSIP")||u.contains("RES_PJSIP")) return "PJSIP";
        if (u.contains("DIAL")||u.contains("HANGUP")||u.contains("ANSWER")||u.contains("BRIDGE")) return "CALL";
        if (u.contains("AMI"))    return "AMI";
        if (u.contains("ERROR"))  return "ERROR";
        if (u.contains("WARNING")||u.contains("WARN")) return "WARN";
        return "INFO";
    }

    private boolean matchesAsteriskLevel(String cat, String filter) {
        if (filter==null||filter.isBlank()||cat==null) return true;
        for (String f : filter.toUpperCase().split(","))
            if (cat.toUpperCase().contains(f.trim())) return true;
        return false;
    }

    private Map<String,Object> buildHourChart(List<Map<String,String>> entries, String levelKey, Set<String> errLevels) {
        Map<String,Integer> byH=new LinkedHashMap<>(), errH=new LinkedHashMap<>();
        for (var e : entries) {
            String ts = e.getOrDefault("ts","");
            String h = ts.length()>=13 ? ts.substring(11,13)+"h" : "?h";
            byH.merge(h,1,Integer::sum);
            if (errLevels.contains(e.getOrDefault(levelKey,""))) errH.merge(h,1,Integer::sum);
        }
        return Map.of("byHour",byH,"errByHour",errH);
    }

    private Map<String,Object> buildAsteriskChart(List<Map<String,String>> entries) {
        Map<String,Integer> byH=new LinkedHashMap<>(), errH=new LinkedHashMap<>();
        for (var e : entries) {
            String ts = e.getOrDefault("ts","");
            String h = ts.length()>=8 ? ts.substring(7,9)+"h" : "?h";
            byH.merge(h,1,Integer::sum);
            String cat = e.getOrDefault("category","");
            if (cat.equals("ERROR")||cat.equals("WARN")||cat.equals("DTLS")) errH.merge(h,1,Integer::sum);
        }
        return Map.of("byHour",byH,"errByHour",errH);
    }

    private void sendAmi(PrintWriter w, Map<String,String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k,v)->sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n"); w.print(sb); w.flush();
    }

    private String readBlock(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder(); String line;
        while ((line=r.readLine())!=null) { if (line.isEmpty()) break; sb.append(line).append("\n"); }
        return sb.toString();
    }

    private Map<String,String> mapOf(String... kv) {
        Map<String,String> m = new LinkedHashMap<>();
        for (int i=0;i<kv.length-1;i+=2) m.put(kv[i],kv[i+1]);
        return m;
    }

    private String extractValue(String block, String key) {
        for (String l : block.split("\n")) if (l.contains(key)) return l.substring(l.indexOf(key)+key.length()).trim();
        return "N/A";
    }

    private String extractFirstLine(String block) {
        for (String l : block.split("\n"))
            if (!l.isBlank()&&!l.startsWith("Response")&&!l.startsWith("Output")) return l.trim();
        return "N/A";
    }

    private int extractChannelCount(String block) {
        for (String l : block.split("\n"))
            if (l.contains("active channel"))
                try { return Integer.parseInt(l.trim().split(" ")[0]); } catch (Exception e) { return 0; }
        return 0;
    }

    private List<Map<String,String>> parseEndpoints(String block) {
        List<Map<String,String>> list = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (line.isBlank()||line.startsWith("Endpoint")||line.startsWith("=")||
                line.startsWith("Response")||line.startsWith("Output")) continue;
            String[] p = line.trim().split("\\s+");
            if (p.length>=2) {
                String name = p[0].contains("/") ? p[0].split("/")[0] : p[0];
                if (name.length()>1&&!name.startsWith("-"))
                    list.add(Map.of("name",name,"status",p[p.length-1]));
            }
        }
        return list;
    }

    private Map<String,String> parseTrunk(String block) {
        for (String l : block.split("\n"))
            if (l.contains("tronco-sip")||l.contains("Registered")||l.contains("Unregistered"))
                return Map.of("name","tronco-sip",
                    "status", l.contains("Registered")&&!l.contains("Unregistered") ? "Registered":"Unregistered");
        return Map.of("name","tronco-sip","status","Unknown");
    }

    private String toJson(Map<String,String> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k,v)->sb.append("\"").append(k).append("\":\"")
            .append(v==null?"":v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"))
            .append("\","));
        if (sb.charAt(sb.length()-1)==',') sb.setCharAt(sb.length()-1,'}'); else sb.append('}');
        return sb.toString();
    }
}
