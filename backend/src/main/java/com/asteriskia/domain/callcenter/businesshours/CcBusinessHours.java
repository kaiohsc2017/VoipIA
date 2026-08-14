package com.asteriskia.domain.callcenter.businesshours;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcBusinessHours — calendário nomeado de horário de funcionamento (Fase 5e.1 do plano de
 * fechamento 5/7/9 do Call Center, V74). Um calendário tem timezone própria e N slots de horário
 * ({@link CcBusinessHoursSlot}) — feriados são compartilhados com {@code cc_holidays} (Fase 26),
 * globais (sem calendário) ou específicos de um calendário.
 */
@Entity
@Table(name = "cc_business_hours")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcBusinessHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 60)
    @Builder.Default
    private String timezone = "America/Sao_Paulo";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
