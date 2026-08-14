package com.asteriskia.domain.callcenter.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CcQualityReport — execução do relatório de qualidade do Call Center (Fase 26 do plano
 * omnicanal Parte III), distinto do relatório de performance por atendente do Insights
 * ({@code AgentPerformanceReport}, V39) — este agrega notas já computadas por
 * {@code CallEvaluation}/{@code CallEvaluationItem} (Fase 8), sem nenhuma chamada de IA nova.
 * {@code source} sempre {@code "callcenter"} nesta fase, mas a coluna existe desde o início para
 * não repetir o gap de {@code agent_evolution_snapshots} (V39, sem coluna source).
 */
@Entity
@Table(name = "cc_quality_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcQualityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String source = "callcenter";

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 10)
    private QualityReportScopeType scopeType;

    @Column(name = "scope_value", length = 200)
    private String scopeValue;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @CreationTimestamp
    @Column(name = "requested_at", updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "nota_media")
    private BigDecimal notaMedia;

    @Column(name = "total_avaliacoes", nullable = false)
    @Builder.Default
    private Integer totalAvaliacoes = 0;

    @Column(name = "total_reprovadas", nullable = false)
    @Builder.Default
    private Integer totalReprovadas = 0;

    @Column(name = "previous_report_id")
    private Long previousReportId;

    /** BUs efetivamente agregadas nesta execução (lista separada por vírgula) — nulo quando quem
     * gerou não tinha restrição de BU (ADMIN). Usado por {@code list()}/{@code getById()} para
     * nunca reexibir a um leitor restrito por BU um conteúdo já calculado que incluiu dado de BU
     * que ele não deveria ver — a restrição em {@code resolveAudioFileIds} só protege a
     * GERAÇÃO, não a releitura do artefato persistido. */
    @Column(name = "scoped_bu_ids", length = 500)
    private String scopedBuIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
    private String contentJson;
}
