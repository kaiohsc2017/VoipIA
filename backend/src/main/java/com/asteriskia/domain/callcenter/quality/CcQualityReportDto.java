package com.asteriskia.domain.callcenter.quality;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record CcQualityReportDto(
        Long id,
        QualityReportScopeType scopeType,
        String scopeValue,
        LocalDate dateFrom,
        LocalDate dateTo,
        String requestedBy,
        OffsetDateTime requestedAt,
        CcQualityReportContent content,
        Long previousReportId,
        CcQualityReportEvolution evolution) {}
