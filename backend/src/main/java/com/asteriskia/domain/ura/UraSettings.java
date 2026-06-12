package com.asteriskia.domain.ura;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * UraSettings — Mensagens configuráveis do fluxo da URA.
 * Chaves fixas: boas_vindas | informativa | encerramento
 */
@Entity
@Table(name = "ura_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UraSettings {

    @Id
    @Column(length = 50)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false)
    private Boolean required;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
