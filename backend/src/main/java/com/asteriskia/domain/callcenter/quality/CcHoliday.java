package com.asteriskia.domain.callcenter.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcHoliday — calendário de feriados (Fase 26 do plano omnicanal Parte III), compartilhado com
 * a Fase 5e.1 (horário de funcionamento do Flow Builder, V74) — uma única tabela, não duas.
 * {@code calendarId} nulo é feriado GLOBAL (fecha todos os calendários de horário); preenchido,
 * fecha só aquele calendário específico — coluna aditiva (V74), a tela de feriados existente
 * (Fase 26) continua só criando feriados globais, atribuir a um calendário fica para quando a UI
 * de horário de funcionamento precisar disso.
 */
@Entity
@Table(name = "cc_holidays")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "holiday_date", nullable = false, unique = true)
    private LocalDate holidayDate;

    @Column(length = 200)
    private String description;

    @Column(name = "calendar_id")
    private Long calendarId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
