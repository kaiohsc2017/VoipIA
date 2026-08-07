package com.asteriskia.domain.callcenter.flow.engine;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcFlowExecutionStep — um nó percorrido dentro de uma {@link CcFlowExecution} (Fase 5b).
 * {@link #detail} nunca guarda dado sensível/entrada livre do usuário — só metadados como o
 * dígito escolhido num menu (a flag de nó sensível chega na sub-fase 5d, quando "coletar entrada"
 * for implementado).
 */
@Entity
@Table(name = "cc_flow_execution_steps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcFlowExecutionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private CcFlowExecution execution;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "node_type", nullable = false, length = 40)
    private String nodeType;

    @Builder.Default
    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt = LocalDateTime.now();

    @Column(name = "exited_at")
    private LocalDateTime exitedAt;

    @Column(name = "taken_edge", length = 64)
    private String takenEdge;

    @Column(columnDefinition = "text")
    private String detail;
}
