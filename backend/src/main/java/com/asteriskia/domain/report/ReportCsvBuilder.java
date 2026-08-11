package com.asteriskia.domain.report;

import com.asteriskia.domain.call.CallRecord;
import com.asteriskia.domain.connectivity.TestResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * ReportCsvBuilder — montagem e escaping dos CSVs de relatório (conectividade, URA, ranking) e o
 * envelope de download HTTP, extraído de ReportController (fase 13 da refatoração). Sem dependência
 * de repositório — recebe as linhas já carregadas e só formata/escapa/monta a resposta.
 */
public final class ReportCsvBuilder {

    private ReportCsvBuilder() {}

    private static final DateTimeFormatter CSV_DT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public static String buildLabelValueCsv(String header, List<Object[]> rows) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.print("﻿");
        pw.println(header);
        for (Object[] row : rows) {
            // Arredonda valores decimais (ex: AVG de duração) para 1 casa — evita precisão de
            // ponto flutuante bruta (ex: "94.3333333333333333") no CSV.
            Object value =
                    row[1] instanceof Number n && !(n instanceof Long || n instanceof Integer)
                            ? Math.round(n.doubleValue() * 10.0) / 10.0
                            : row[1];
            pw.printf("%s,%s%n", esc(String.valueOf(row[0])), String.valueOf(value));
        }
        pw.flush();
        return sw.toString();
    }

    public static String buildConnectivityCsv(List<TestResult> results) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        // BOM UTF-8 para compatibilidade com Excel
        pw.print("﻿");
        pw.println("ID,Data/Hora,Número,BU,Cliente,Operação,Segmento,Status,Código SIP,Motivo SIP");

        for (TestResult r : results) {
            var nt = r.getNumberTest();
            pw.printf(
                    "%d,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    r.getId(),
                    r.getExecutedAt() != null ? r.getExecutedAt().format(CSV_DT) : "",
                    esc(nt != null ? nt.getPhoneNumber() : ""),
                    esc(
                            nt != null && nt.getBusinessUnit() != null
                                    ? nt.getBusinessUnit().getName()
                                    : ""),
                    esc(nt != null && nt.getClient() != null ? nt.getClient().getName() : ""),
                    esc(nt != null && nt.getOperation() != null ? nt.getOperation().getName() : ""),
                    esc(nt != null && nt.getSegment() != null ? nt.getSegment().getName() : ""),
                    esc(r.getStatus()),
                    r.getSipResponseCode() != null ? r.getSipResponseCode().toString() : "",
                    esc(r.getSipResponseReason()));
        }

        pw.flush();
        return sw.toString();
    }

    public static String buildUraCsv(List<CallRecord> calls) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.print("﻿");
        pw.println("ID,Data/Hora,Número,Cliente,Chamado Jira,Status Jira,Duração (s),Transcrição");

        for (CallRecord c : calls) {
            pw.printf(
                    "%d,%s,%s,%s,%s,%s,%d,%s%n",
                    c.getId(),
                    c.getCallDate() != null ? c.getCallDate().format(CSV_DT) : "",
                    esc(c.getCallerNumber()),
                    esc(c.getClientName()),
                    esc(c.getJiraIssueKey()),
                    esc(c.getJiraIssueStatus()),
                    c.getCallDurationSecs() != null ? c.getCallDurationSecs() : 0,
                    esc(c.getTranscription()));
        }

        pw.flush();
        return sw.toString();
    }

    /**
     * Escapa campo CSV (entre aspas se contiver vírgula, aspas ou quebra de linha).
     *
     * <p>Achado de segurança (CSV/fórmula injection): campos como transcrição de chamada são
     * influenciáveis por quem liga — um valor começando com =/+/-/@ vira fórmula executada ao abrir
     * no Excel/LibreOffice. Prefixa com apóstrofo antes de aplicar o escaping de CSV padrão.
     */
    public static String esc(String value) {
        if (value == null) return "";
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public static ResponseEntity<byte[]> csvResponse(String csv, String filename) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(bytes);
    }
}
