package com.asteriskia.domain.insights;

import java.util.List;

/**
 * InsightsMetadataUpdateRequest — payload do backfill metadata-only
 * (insights/src/backfill_metadata.py). Reenvia só os campos novos do grupo
 * A/B/C/D (V43) de uma chamada JÁ processada, sem tocar transcrição/insights/
 * avaliação — nunca passa por InsightsIngestionService.ingest().
 */
public record InsightsMetadataUpdateRequest(
        String agentLoginId,
        String customerNumber,
        String organization,
        String disconnectedBy,
        Integer numberOfHolds,
        Integer totalHoldTime,
        Integer numberOfTransfers,
        Integer numberOfConferences,
        Integer wrapupTime,
        String codec,
        Integer missedRtpPackets,
        Integer decodingErrors,
        String switchCallId,
        String trunk,
        String captureType,
        String datasourceName,
        List<IngestInsightsRequest.TransferEventPayload> transferEvents
) {}
