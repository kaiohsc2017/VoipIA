package com.asteriskia.domain.callcenter.supervision;

import com.asteriskia.domain.callcenter.CcAgent;
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

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "agent_id", nullable = false)
    private CcAgent agent;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private SupervisionActionType actionType;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;
}
