package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcAgent;
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
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcAgentSchedule — turno esperado de um agente num dia da semana (sub-fase 9c.7). Base do
 * cálculo de aderência à escala — deliberadamente separada de {@code cc_business_hours} (V74,
 * horário da fila/operação): o turno de um agente individual não precisa coincidir com o horário
 * de atendimento da fila (folga, meio período, etc.).
 */
@Entity
@Table(name = "cc_agent_schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAgentSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "agent_id", nullable = false)
    private CcAgent agent;

    /** ISO-8601: 1=segunda, 7=domingo — mesmo padrão de {@code cc_business_hours_slots} (V74). */
    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
