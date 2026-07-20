package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * AgentPerformanceReport — relatório de performance de um atendente num período,
 * pedido por um supervisor (Fase 2 do Quality Management, V39). Posse é sempre por
 * {@code requestedBy} (username, mesmo padrão do JWT sem user-id usado no resto do
 * projeto) — aplicada no service, não na entidade.
 */
@Entity
@Table(name = "agent_performance_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentPerformanceReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_name", nullable = false, length = 200)
    private String agentName;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "requested_at", insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "pending";

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", columnDefinition = "jsonb")
    private String contentJson;

    @Column(name = "llm_tokens_in")
    @Builder.Default
    private Integer llmTokensIn = 0;

    @Column(name = "llm_tokens_out")
    @Builder.Default
    private Integer llmTokensOut = 0;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "previous_report_id")
    private Long previousReportId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evolution_json", columnDefinition = "jsonb")
    private String evolutionJson;
}
