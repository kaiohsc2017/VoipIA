package com.asteriskia.domain.masterdata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Operation — Operação cadastrada, vinculada a clientes (Módulo 2).
 */
@Entity
@Table(name = "operations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Operation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 200)
    private String name;

    @Column(length = 300)
    private String description;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /** Lado inverso de Client.operations — apenas leitura, evita ciclo de serialização. */
    @ManyToMany(mappedBy = "operations", fetch = FetchType.LAZY)
    @Builder.Default
    @JsonIgnore
    private Set<Client> clients = new HashSet<>();

    /**
     * Unidades de Negócio (BU) às quais a operação pode ser associada — opcional, múltiplo.
     * EAGER (não LAZY): serializado direto pelo Jackson e spring.jpa.open-in-view=false
     * neste projeto — LAZY aqui vira LazyInitializationException fora de transação.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "operation_business_units",
            joinColumns = @JoinColumn(name = "operation_id"),
            inverseJoinColumns = @JoinColumn(name = "business_unit_id")
    )
    @Builder.Default
    private Set<BusinessUnit> businessUnits = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
