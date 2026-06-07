package com.asteriskia.domain.connectivity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * TestResult — Resultado de uma execução de teste de conectividade (Módulo 2).
 *
 * Status possíveis: SUCESSO | FALHA | OCUPADO | SEM_RESPOSTA | INVALIDO | TIMEOUT | INDISPONIVEL | RECUSADO
 */
@Entity
@Table(name = "test_results")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "number_test_id", nullable = false)
    private NumberTest numberTest;

    @NotNull
    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    @Column(name = "sip_response_code")
    private Integer sipResponseCode;

    @Column(name = "sip_response_reason", length = 100)
    private String sipResponseReason;

    @NotBlank
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "execution_order")
    @Builder.Default
    private Integer executionOrder = 1;

    @Column(name = "next_scheduled_at")
    private LocalDateTime nextScheduledAt;

    @Column(name = "asterisk_call_id", length = 100)
    private String asteriskCallId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
