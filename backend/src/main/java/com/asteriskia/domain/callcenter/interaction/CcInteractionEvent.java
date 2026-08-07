package com.asteriskia.domain.callcenter.interaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcInteractionEvent — traço bruto de eventos AMI recebidos para uma {@link CcInteraction}
 * (fila, oferta, atendimento, encerramento). Permite depurar exatamente o que o Asterisk
 * reportou para cada chamada, sem depender de logs de aplicação.
 */
@Entity
@Table(name = "cc_interaction_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcInteractionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interaction_id", nullable = false)
    private CcInteraction interaction;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Lob
    private String details;

    @CreationTimestamp
    @Column(name = "occurred_at", updatable = false)
    private LocalDateTime occurredAt;
}
