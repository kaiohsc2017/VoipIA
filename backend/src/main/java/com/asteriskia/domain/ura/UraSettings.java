package com.asteriskia.domain.ura;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * UraSettings — Mensagens/configurações do fluxo de uma URA específica.
 * Chaves usadas: boas_vindas | informativa | encerramento | vad_aggressiveness
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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ura_id", nullable = false)
    private Integer uraId;

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
