package com.asteriskia.domain.masterdata;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Segment — Segmento de negócio para categorização de testes (Módulo 2).
 *
 * Os campos {@code default*} definem o template padrão de agendamento
 * aplicado automaticamente a todo NumberTest criado a partir do DATACENTER
 * para um número deste segmento — evita nascer com teste "zerado/pendente"
 * quando o segmento já tem uma cadência conhecida.
 */
@Entity
@Table(name = "segments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Segment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 300)
    private String description;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @JsonFormat(pattern = "HH:mm:ss")
    @Column(name = "default_start_time")
    private LocalTime defaultStartTime;

    @Column(name = "default_interval_minutes")
    private Integer defaultIntervalMinutes;

    @Column(name = "default_quantity")
    private Integer defaultQuantity;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
