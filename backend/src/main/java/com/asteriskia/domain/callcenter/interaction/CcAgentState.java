package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcPauseReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * CcAgentState — uma linha por período contínuo em um estado (histórico completo, nunca
 * atualizado in-place: uma troca de estado fecha a linha anterior com {@code endedAt} e abre uma
 * nova). É a matéria-prima de ocupação/aderência/ACW pedida no plano do Call Center.
 */
@Entity
@Table(name = "cc_agent_states")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAgentState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "agent_id", nullable = false)
    private CcAgent agent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentState state;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "pause_reason_id")
    private CcPauseReason pauseReason;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;
}
