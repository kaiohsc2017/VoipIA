package com.asteriskia.domain.callcenter.flow.audio;

import com.asteriskia.domain.masterdata.BusinessUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** CcAudioFile — biblioteca de áudios do Flow Builder (Fase 5c). Sempre PCM 8kHz/16-bit mono. */
@Entity
@Table(name = "cc_audio_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAudioFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "file_name", nullable = false, unique = true, length = 200)
    private String fileName;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String format = "wav";

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
