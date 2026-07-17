package com.asteriskia.domain.insights;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * IngestInsightsRequest — payload enviado pelo serviço asteriskia-insights ao
 * final do processamento de uma chamada (POST /api/v1/internal/insights).
 * Estrutura espelha exatamente o payload montado em insights/src/main.py::_build_payload.
 */
public record IngestInsightsRequest(
        @NotBlank String callRef,
        @NotBlank String wavPath,
        @NotBlank String xmlPath,
        Integer durationSeconds,
        OffsetDateTime callStarttime,
        String agentName,
        String agentIdVerint,
        String extension,
        String ani,
        String dnis,
        String direction,
        String skill,
        JsonNode xmlRaw,
        Integer sttTokensIn,
        Integer sttTokensOut,
        String sttModel,
        Integer llmTokensIn,
        Integer llmTokensOut,
        String llmModel,
        // Achado em produção (2026-07-17): @NotEmpty rejeitava com 400 chamadas cuja
        // transcrição veio com 0 segmentos (ex: áudio muito curto/silencioso) — como a
        // ingestão falhava sem persistir, o watcher reprocessava a MESMA chamada pra
        // sempre a cada ciclo de poll, gastando API do Gemini sem nunca conseguir
        // persistir uma chamada genuinamente sem fala. @NotNull permite lista vazia.
        @NotNull @Valid List<SegmentPayload> segments,
        @NotNull @Valid InsightsPayload insights,
        @Valid List<FindingPayload> findings
) {
    public record SegmentPayload(
            @NotBlank String speaker,
            @NotNull Integer startMs,
            @NotNull Integer endMs,
            @NotBlank String text,
            String toneSemantic,
            String toneAcoustic
    ) {}

    public record InsightsPayload(
            String resumo,
            String categoriaAssunto,
            String sentimentoGeral,
            Double aderenciaScript,
            @NotBlank String criticidade,
            JsonNode insightsJson
    ) {}

    public record FindingPayload(
            @NotBlank String tipo,
            @NotBlank String descricao,
            String trechoReferencia,
            String prioridade
    ) {}
}
