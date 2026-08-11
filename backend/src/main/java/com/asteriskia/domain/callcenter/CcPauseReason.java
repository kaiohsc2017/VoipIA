package com.asteriskia.domain.callcenter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcPauseReason — motivo de pausa do agente (tabela criada na V47/Fase 2, entidade só chega
 * agora na Fase 4, quando o estado do agente passa a existir de fato).
 */
@Entity
@Table(name = "cc_pause_reasons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcPauseReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false)
    @Builder.Default
    private Boolean productive = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
