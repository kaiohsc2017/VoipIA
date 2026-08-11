package com.asteriskia.domain.settings;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * SettingsHistory — registra cada alteração de variável de ambiente
 * realizada via interface web (Módulo de Configurações, Fase 12).
 *
 * Campos secretos são gravados mascarados (••••••••) para auditoria
 * sem expor senhas/tokens no banco de dados.
 */
@Entity
@Table(name = "settings_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettingsHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Momento da alteração (com timezone). */
    @Column(name = "changed_at", nullable = false, updatable = false)
    private OffsetDateTime changedAt;

    /** Usuário que fez a alteração (obtido do JWT). */
    @Column(name = "changed_by", nullable = false, length = 120)
    private String changedBy;

    /** Nome da variável de ambiente alterada. */
    @Column(name = "env_key", nullable = false, length = 255)
    private String envKey;

    /** Valor anterior (null em criação). Mascarado se for campo secreto. */
    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    /** Novo valor. Mascarado se for campo secreto. */
    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    /** IP de origem da requisição. */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @PrePersist
    void prePersist() {
        if (changedAt == null) changedAt = OffsetDateTime.now();
    }
}
