package com.asteriskia.domain.insights;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** UploadBatchDto — lote de upload do portal do supervisor (Fase 3 do Quality
 * Management, V40), com o resumo de cada arquivo quando consultado em detalhe. */
public record UploadBatchDto(
        UUID id,
        String uploadedBy,
        OffsetDateTime createdAt,
        Integer fileCount,
        String notes,
        List<UploadFileSummary> files
) {
    public static UploadBatchDto summary(UploadBatch batch) {
        return new UploadBatchDto(batch.getId(), batch.getUploadedBy(), batch.getCreatedAt(),
                batch.getFileCount(), batch.getNotes(), null);
    }

    public static UploadBatchDto detail(UploadBatch batch, List<UploadFileSummary> files) {
        return new UploadBatchDto(batch.getId(), batch.getUploadedBy(), batch.getCreatedAt(),
                batch.getFileCount(), batch.getNotes(), files);
    }

    public record UploadFileSummary(
            Long id, String callRef, String agentName, String direction,
            String status, String errorMsg, Integer durationSeconds
    ) {
        public static UploadFileSummary from(CallAudioFile a) {
            return new UploadFileSummary(a.getId(), a.getCallRef(), a.getAgentName(), a.getDirection(),
                    a.getStatus(), a.getErrorMsg(), a.getDurationSeconds());
        }
    }
}
