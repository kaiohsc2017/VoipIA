package com.asteriskia.domain.callcenter.quality;

import java.math.BigDecimal;
import java.util.List;

/** CcQualityReportContent — conteúdo agregado de uma execução do relatório de qualidade (Fase
 * 26), persistido em {@code cc_quality_reports.content_json}. */
public record CcQualityReportContent(
        BigDecimal notaMedia,
        Integer totalAvaliacoes,
        Integer totalReprovadas,
        List<ItemAverage> notaPorItem) {

    public record ItemAverage(Long itemId, String pergunta, BigDecimal media) {}
}
