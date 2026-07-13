package com.asteriskia.domain.logs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LogLineParser — parsing e formatação de linhas de log, extraído de LogsController (fase 4 da
 * refatoração). Puramente funcional (sem I/O, sem estado): detecção de nível/categoria, filtragem
 * por texto, montagem de gráfico por hora e serialização mínima para SSE.
 */
public final class LogLineParser {

    private LogLineParser() {}

    // ── Docker ───────────────────────────────────────────────────────────────

    static Map<String, String> parseLine(String svc, String raw) {
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

    static String detectDockerLevel(String msg) {
        if (msg == null) return "INFO";
        String u = msg.toUpperCase();
        if (u.contains("ERROR") || u.contains("EXCEPTION")) return "ERROR";
        if (u.contains("WARN")) return "WARN";
        if (u.contains("DEBUG")) return "DEBUG";
        return "INFO";
    }

    static boolean matchesLevel(String line, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String u = line.toUpperCase();
        for (String f : filter.toUpperCase().split(",")) if (u.contains(f.trim())) return true;
        return false;
    }

    // ── Asterisk ─────────────────────────────────────────────────────────────

    static Map<String, String> parseAsteriskLine(String raw) {
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

    static String detectAsteriskCategory(String line) {
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

    static boolean matchesAsteriskLevel(String cat, String filter) {
        if (filter == null || filter.isBlank() || cat == null) return true;
        for (String f : filter.toUpperCase().split(","))
            if (cat.toUpperCase().contains(f.trim())) return true;
        return false;
    }

    // ── Gráficos ─────────────────────────────────────────────────────────────

    static Map<String, Object> buildHourChart(
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

    static Map<String, Object> buildAsteriskChart(List<Map<String, String>> entries) {
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

    // ── Serialização mínima para SSE ────────────────────────────────────────

    static String toJson(Map<String, String> m) {
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
