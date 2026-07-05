package com.asteriskia.domain.datacenter;

import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.Client;
import com.asteriskia.domain.masterdata.Operation;
import com.asteriskia.domain.masterdata.Segment;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * PhoneNumber — cadastro central de números (DATACENTER).
 *
 * Fonte da verdade de um número (DDR, 0800 ou WhatsApp) e a qual BU/Cliente
 * ele pertence. Operação e Segmento são opcionais na criação — quando ficam
 * em branco, o número aparece como "pendente" no Cliente e não gera teste de
 * conectividade até serem completados (ver PhoneNumberSyncService).
 */
@Entity
@Table(name = "phone_numbers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PhoneNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "number_type", nullable = false, length = 20)
    private NumberType numberType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id", nullable = false)
    private BusinessUnit businessUnit;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    /** Opcional — número fica "pendente" enquanto não for definida. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "operation_id")
    private Operation operation;

    /** Opcional — número fica "pendente" enquanto não for definido. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "segment_id")
    private Segment segment;

    @Column(length = 300)
    private String observation;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
