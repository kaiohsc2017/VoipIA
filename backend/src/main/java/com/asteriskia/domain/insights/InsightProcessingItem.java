package com.asteriskia.domain.insights;

import java.io.File;
import java.time.LocalDateTime;

/** InsightProcessingItem — uma linha da aba "Processamento": status de um arquivo .wav/.xml
 * descoberto em /opt/audio, do momento em que foi visto até concluir (ou falhar). */
public record InsightProcessingItem(
        Long id,
        String callRef,
        String fileName,
        String status,
        LocalDateTime ingestedAt,
        LocalDateTime startedAt,
        LocalDateTime processedAt,
        String errorMsg,
        Integer queuePosition
) {
    public static InsightProcessingItem from(CallAudioFile a, Integer queuePosition) {
        String fileName = a.getWavPath() != null ? new File(a.getWavPath()).getName() : a.getCallRef();
        return new InsightProcessingItem(
                a.getId(), a.getCallRef(), fileName, a.getStatus(),
                a.getIngestedAt(), a.getStartedAt(), a.getProcessedAt(), a.getErrorMsg(),
                queuePosition);
    }
}
