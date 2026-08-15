package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcReportSchedule — agendamento de exportação periódica do relatório de chamada/chat (sub-fase
 * 9c.6). Escopo (fila/agente/período) e destinatário são congelados na criação e reavaliados só
 * na execução — mesma disciplina fail-closed já aplicada em {@code CcQualityReport} (achado HIGH
 * real da Fase 26): a execução roda fora da sessão de quem criou o agendamento.
 */
@Entity
@Table(name = "cc_report_schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcReportSchedule {

    public enum ReportType { CALLS_EXCEL, CALLS_PDF, CHATS_EXCEL, CHATS_PDF }

    public enum Frequency { DAILY, WEEKLY, MONTHLY }

    public enum Channel { telegram, email }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "report_type", nullable = false, length = 20)
    private String reportType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "queue_id")
    private CcQueue queue;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "agent_id")
    private CcAgent agent;

    @Builder.Default
    @Column(name = "period_days", nullable = false)
    private Integer periodDays = 7;

    @Column(nullable = false, length = 10)
    private String frequency;

    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Builder.Default
    @Column(name = "hour_of_day", nullable = false)
    private Integer hourOfDay = 8;

    @Column(nullable = false, length = 10)
    private String channel;

    @Column(nullable = false, length = 255)
    private String recipient;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "last_run_status", length = 20)
    private String lastRunStatus;
}
