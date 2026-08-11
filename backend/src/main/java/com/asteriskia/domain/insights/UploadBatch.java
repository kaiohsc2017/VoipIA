package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * UploadBatch — um lote de até 100 arquivos enviados de uma vez pelo supervisor no
 * portal de upload (Fase 3 do Quality Management, V40). Cada arquivo do lote vira uma
 * linha em call_audio_files (source='upload', uploadBatchId=this.id), reusando o mesmo
 * pipeline de STT/análise/avaliação do fluxo Verint.
 */
@Entity
@Table(name = "upload_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadBatch {

    @Id
    private UUID id;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "file_count", nullable = false)
    private Integer fileCount;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
