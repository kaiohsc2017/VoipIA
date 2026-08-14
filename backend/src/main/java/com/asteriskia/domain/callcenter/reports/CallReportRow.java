package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * CallReportRow — uma linha do relatório analítico de chamada (Fase 9c). {@code audioFileId},
 * quando presente, é o mesmo id já usado pelos endpoints de detalhe/áudio do Insights do Call
 * Center (Fase 8, {@code GET /api/v1/callcenter/insights/calls/{id}} e {@code .../audio}) — este
 * relatório não duplica streaming nem a busca de transcrição/achados, só aponta pra lá.
 * {@code findingsByTipo} usa o vocabulário real de {@code CallInsightFinding.tipo}
 * (melhoria/falha/treinamento/tendencia) — não existe uma categoria "ponto forte" nos dados
 * (só tipos de achado a melhorar), então o relatório expõe as contagens reais em vez de
 * inventar uma classificação forte/fraco sem lastro no schema.
 */
public record CallReportRow(
        Long interactionId,
        LocalDateTime queuedAt,
        LocalDateTime answeredAt,
        LocalDateTime endedAt,
        String direction,
        String ani,
        String queueName,
        String agentName,
        Long waitSeconds,
        BigDecimal npsScore,
        String flowName,
        String chosenOptionDigit,
        String chosenOptionLabel,
        Long audioFileId,
        String categoriaAssunto,
        String sentimentoGeral,
        String criticidade,
        Map<String, Long> findingsByTipo) {}
