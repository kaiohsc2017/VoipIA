package com.asteriskia.domain.callcenter.reports;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.asteriskia.domain.callcenter.interaction.Direction;

/** CallReportFilter — filtros do relatório analítico de chamada (Fase 9c). Todos os campos são
 * opcionais (nulo = sem restrição naquela dimensão). */
public record CallReportFilter(
        LocalDateTime from,
        LocalDateTime to,
        Long queueId,
        Long agentId,
        Direction direction,
        BigDecimal npsMin,
        BigDecimal npsMax,
        Long waitMinSeconds,
        Long waitMaxSeconds,
        String chosenOptionDigit,
        String transcriptText) {}
