package com.asteriskia.domain.callcenter.reports;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterReportExportService — exportação Excel/PDF do relatório analítico de chamada/chat
 * (sub-fase 9c.5 do plano modulo-callcenter-omnicanal.plan.md). Reaproveita bibliotecas já
 * presentes no projeto — Apache POI (mesma de {@code ExcelExportService}) e openhtmltopdf (mesma
 * de {@code AgentReportPdfService}) — sem adicionar nenhuma dependência nova (a decisão original
 * do plano, D3, previa OpenPDF; descartada ao descobrir que o projeto já resolve PDF via HTML
 * com openhtmltopdf, sem a licença AGPL do iText que motivou a decisão original).
 *
 * <p>Teto de 50 mil linhas nos dois formatos — acima disso, 413 com mensagem pedindo um filtro
 * mais estreito (mesmo espírito do teto de página já aplicado em {@code CallCenterReportsController}).
 *
 * <p>Dois escapes distintos, nunca compartilhados: {@link #escExcel} só previne injeção de
 * fórmula (=/+/-/@) em campos influenciáveis por quem liga/conversa (mesma classe de achado já
 * corrigida em {@code ReportCsvBuilder.esc}, mas sem o quoting de CSV, que não se aplica a uma
 * célula do POI); {@link #escHtml} escapa entidades HTML (mesmo padrão de
 * {@code AgentReportPdfService.esc}), necessário porque o PDF é gerado renderizando HTML — sem
 * isso, um nome de cliente com {@code <}/{@code &} quebraria a tabela do PDF.
 */
@Service
@RequiredArgsConstructor
public class CallCenterReportExportService {

    private static final int MAX_ROWS = 50_000;

    private final CallCenterDetailReportService detailReportService;

    public byte[] exportCallsExcel(CallReportFilter filter) {
        List<CallReportRow> rows = fetchAllCalls(filter);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Chamadas");
            String[] headers = {"Data/Hora", "Direção", "ANI", "Fila", "Agente", "Espera (s)", "NPS",
                    "Fluxo", "Opção escolhida", "Categoria", "Sentimento", "Criticidade"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            int rowIdx = 1;
            for (CallReportRow r : rows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(str(r.queuedAt()));
                row.createCell(1).setCellValue(escExcel(r.direction()));
                row.createCell(2).setCellValue(escExcel(r.ani()));
                row.createCell(3).setCellValue(escExcel(r.queueName()));
                row.createCell(4).setCellValue(escExcel(r.agentName()));
                row.createCell(5).setCellValue(r.waitSeconds() != null ? r.waitSeconds() : 0);
                row.createCell(6).setCellValue(r.npsScore() != null ? r.npsScore().doubleValue() : 0);
                row.createCell(7).setCellValue(escExcel(r.flowName()));
                row.createCell(8).setCellValue(escExcel(r.chosenOptionLabel()));
                row.createCell(9).setCellValue(escExcel(r.categoriaAssunto()));
                row.createCell(10).setCellValue(escExcel(r.sentimentoGeral()));
                row.createCell(11).setCellValue(escExcel(r.criticidade()));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar Excel do relatório de chamadas", e);
        }
    }

    public byte[] exportCallsPdf(CallReportFilter filter) {
        List<CallReportRow> rows = fetchAllCalls(filter);
        StringBuilder html = new StringBuilder(htmlHeader("Relatório de chamadas"));
        html.append("<table><tr><th>Data/Hora</th><th>Direção</th><th>ANI</th><th>Fila</th><th>Agente</th>")
                .append("<th>Espera (s)</th><th>NPS</th><th>Categoria</th></tr>");
        for (CallReportRow r : rows) {
            html.append("<tr><td>").append(str(r.queuedAt())).append("</td><td>").append(escHtml(r.direction()))
                    .append("</td><td>").append(escHtml(r.ani())).append("</td><td>").append(escHtml(r.queueName()))
                    .append("</td><td>").append(escHtml(r.agentName())).append("</td><td>")
                    .append(r.waitSeconds() != null ? r.waitSeconds() : "").append("</td><td>")
                    .append(r.npsScore() != null ? r.npsScore().toPlainString() : "").append("</td><td>")
                    .append(escHtml(r.categoriaAssunto())).append("</td></tr>");
        }
        html.append("</table></body></html>");
        return renderPdf(html.toString());
    }

    public byte[] exportChatsExcel(ChatReportFilter filter) {
        List<ChatReportRow> rows = fetchAllChats(filter);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Chats");
            String[] headers = {"Início", "Assumido em", "Encerrado em", "Cliente", "Fila", "Agente", "Tabulação"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            int rowIdx = 1;
            for (ChatReportRow r : rows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(str(r.startedAt()));
                row.createCell(1).setCellValue(str(r.claimedAt()));
                row.createCell(2).setCellValue(str(r.closedAt()));
                row.createCell(3).setCellValue(escExcel(r.customerName() != null ? r.customerName() : r.customerRef()));
                row.createCell(4).setCellValue(escExcel(r.queueName()));
                row.createCell(5).setCellValue(escExcel(r.agentName()));
                row.createCell(6).setCellValue(escExcel(r.dispositionName()));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar Excel do relatório de chats", e);
        }
    }

    public byte[] exportChatsPdf(ChatReportFilter filter) {
        List<ChatReportRow> rows = fetchAllChats(filter);
        StringBuilder html = new StringBuilder(htmlHeader("Relatório de chats"));
        html.append("<table><tr><th>Início</th><th>Cliente</th><th>Fila</th><th>Agente</th><th>Tabulação</th></tr>");
        for (ChatReportRow r : rows) {
            html.append("<tr><td>").append(str(r.startedAt())).append("</td><td>")
                    .append(escHtml(r.customerName() != null ? r.customerName() : r.customerRef())).append("</td><td>")
                    .append(escHtml(r.queueName())).append("</td><td>").append(escHtml(r.agentName())).append("</td><td>")
                    .append(escHtml(r.dispositionName())).append("</td></tr>");
        }
        html.append("</table></body></html>");
        return renderPdf(html.toString());
    }

    private List<CallReportRow> fetchAllCalls(CallReportFilter filter) {
        Pageable pageable = PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "queuedAt"));
        Page<CallReportRow> page = detailReportService.searchCalls(filter, pageable);
        assertWithinLimit(page);
        return page.getContent();
    }

    private List<ChatReportRow> fetchAllChats(ChatReportFilter filter) {
        Pageable pageable = PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<ChatReportRow> page = detailReportService.searchChats(filter, pageable);
        assertWithinLimit(page);
        return page.getContent();
    }

    private void assertWithinLimit(Page<?> page) {
        if (page.getTotalElements() > MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "O período/filtro selecionado tem mais de " + MAX_ROWS
                            + " linhas — estreite o período ou os filtros antes de exportar.");
        }
    }

    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar PDF do relatório", e);
        }
    }

    private String htmlHeader(String title) {
        return "<html><head><meta charset=\"UTF-8\"/><style>"
                + "body{font-family:sans-serif;font-size:11px;color:#222}"
                + "h1{font-size:16px}"
                + "table{width:100%;border-collapse:collapse;margin-top:8px}"
                + "td,th{border:1px solid #ddd;padding:3px 6px;text-align:left;font-size:9px}"
                + "</style></head><body><h1>" + escHtml(title) + "</h1>";
    }

    private String str(Object value) {
        return value != null ? value.toString() : "";
    }

    private String escExcel(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private String escHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
