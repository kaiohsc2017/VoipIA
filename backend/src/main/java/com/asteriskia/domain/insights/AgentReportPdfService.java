package com.asteriskia.domain.insights;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * AgentReportPdfService — exporta um relatório de performance concluído em PDF (Fase 2
 * do Quality Management, V39). HTML montado diretamente aqui (sem motor de template
 * novo — o projeto não usa Thymeleaf em nenhum outro lugar, YAGNI) e renderizado com
 * openhtmltopdf. Único ponto do backend que gera HTML server-side.
 */
@Service
public class AgentReportPdfService {

    public byte[] render(AgentReportDto report) {
        String html = buildHtml(report);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar PDF do relatório id=" + report.id(), e);
        }
    }

    private String buildHtml(AgentReportDto report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\"/><style>")
                .append("body{font-family:sans-serif;font-size:12px;color:#222}")
                .append("h1{font-size:18px}h2{font-size:14px;margin-top:20px;border-bottom:1px solid #ccc}")
                .append("table{width:100%;border-collapse:collapse;margin-top:8px}")
                .append("td,th{border:1px solid #ddd;padding:4px 8px;text-align:left;font-size:11px}")
                .append(".muted{color:#666}")
                .append("</style></head><body>");

        sb.append("<h1>Relatório de performance — ").append(esc(report.agentName())).append("</h1>");
        sb.append("<p class=\"muted\">Período: ").append(report.dateFrom()).append(" a ").append(report.dateTo())
                .append(" — solicitado por ").append(esc(report.requestedBy()))
                .append(" em ").append(report.requestedAt()).append("</p>");

        AgentReportContent content = report.content();
        if (content != null && content.aggregate() != null) {
            AgentReportContent.Aggregate agg = content.aggregate();
            sb.append("<h2>Resumo</h2><table>")
                    .append(row("Chamadas no período", String.valueOf(agg.totalChamadas())))
                    .append(row("Nota média", formatNota(agg.notaMedia())))
                    .append(row("Auto-fails", String.valueOf(agg.autoFails())))
                    .append("</table>");

            if (!agg.notaPorItem().isEmpty()) {
                sb.append("<h2>Nota por item da ficha</h2><table><tr><th>Item</th><th>Nota média</th></tr>");
                for (AgentReportContent.ItemAverage item : agg.notaPorItem()) {
                    sb.append("<tr><td>").append(esc(item.pergunta())).append("</td><td>")
                            .append(formatNota(item.media())).append("</td></tr>");
                }
                sb.append("</table>");
            }
        }

        AgentReportEvolution evolution = report.evolution();
        if (evolution != null) {
            sb.append("<h2>Evolução desde o relatório anterior</h2>");
            if (evolution.partial()) {
                sb.append("<p class=\"muted\">Ficha de avaliação alterada entre os períodos — comparação parcial.</p>");
            }
            sb.append("<p>Delta nota média: ").append(formatDelta(evolution.deltaNotaMedia())).append("</p>");
            if (evolution.deltaPorItem() != null && !evolution.deltaPorItem().isEmpty()) {
                sb.append("<table><tr><th>Item</th><th>Anterior</th><th>Atual</th><th>Delta</th></tr>");
                for (AgentReportEvolution.ItemDelta d : evolution.deltaPorItem()) {
                    sb.append("<tr><td>").append(esc(d.pergunta())).append("</td><td>")
                            .append(formatNota(d.anterior())).append("</td><td>")
                            .append(formatNota(d.atual())).append("</td><td>")
                            .append(formatDelta(d.delta())).append("</td></tr>");
                }
                sb.append("</table>");
            }
        }

        if (content != null && content.narrative() != null) {
            AgentReportContent.Narrative n = content.narrative();
            sb.append("<h2>Pontos fortes</h2>").append(listOrEmpty(n.pontosFortes()));
            sb.append("<h2>Pontos de melhoria</h2>").append(listOrEmpty(n.pontosMelhoria()));
            sb.append("<h2>Recomendações</h2>").append(listOrEmpty(n.recomendacoes()));
            if (n.comparacaoTextual() != null && !n.comparacaoTextual().isBlank()) {
                sb.append("<h2>Comparação com o período anterior</h2><p>").append(esc(n.comparacaoTextual())).append("</p>");
            }
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private String listOrEmpty(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "<p class=\"muted\">Nenhum registrado.</p>";
        }
        StringBuilder sb = new StringBuilder("<ul>");
        for (String item : items) {
            sb.append("<li>").append(esc(item)).append("</li>");
        }
        return sb.append("</ul>").toString();
    }

    private String row(String label, String value) {
        return "<tr><td>" + esc(label) + "</td><td>" + esc(value) + "</td></tr>";
    }

    private String formatNota(BigDecimal value) {
        return value != null ? value.toPlainString() : "—";
    }

    private String formatDelta(BigDecimal value) {
        if (value == null) return "—";
        String sign = value.signum() > 0 ? "+" : "";
        return sign + value.toPlainString();
    }

    private String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
