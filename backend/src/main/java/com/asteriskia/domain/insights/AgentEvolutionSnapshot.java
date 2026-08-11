package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * AgentEvolutionSnapshot — ponto de série temporal por agente/métrica (Fase 2 do
 * Quality Management, V39). Existe separado de {@code evolution_json} do relatório
 * para permitir ao supervisor navegar o histórico de evolução do agente independente
 * de abrir um relatório específico. itemId nullable cobre métricas sem ficha (ex:
 * nota_total consolidada, sentimento médio).
 */
@Entity
@Table(name = "agent_evolution_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentEvolutionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_name", nullable = false, length = 200)
    private String agentName;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "metric_key", nullable = false, length = 100)
    private String metricKey;

    @Column(name = "valor", nullable = false)
    private BigDecimal valor;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
