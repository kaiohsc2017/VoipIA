package com.asteriskia.domain.callcenter.supervision;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * CcSupervisionAction — auditoria de ação de supervisão (Fase 6): escuta, sussurro,
 * interceptação (barge-in), pausa/despausa forçada. Escuta de conversa exige rastro — LGPD
 * art. 37, mesmo motivo que já existe para reprodução de gravação ({@code CallCenterRecordingController}).
 */
@Entity
@Table(name = "cc_supervision_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcSupervisionAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supervisor_user_id", nullable = false)
    private Integer supervisorUserId;

    /** Nulo para {@code REDIRECT_QUEUE}/{@code REDIRECT_AGENT} sobre uma chamada ainda em fila,
     * sem agente atribuído (Fase 15.3) — nas demais ações é sempre o agente monitorado/afetado. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "agent_id")
    private CcAgent agent;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private SupervisionActionType actionType;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** Destino de {@code REDIRECT_QUEUE} — mutuamente exclusivo com {@link #targetAgent}. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "target_queue_id")
    private CcQueue targetQueue;

    /** Destino de {@code REDIRECT_AGENT} — mutuamente exclusivo com {@link #targetQueue}. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "target_agent_id")
    private CcAgent targetAgent;
}
