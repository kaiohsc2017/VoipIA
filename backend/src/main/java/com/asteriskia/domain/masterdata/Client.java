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
 * Client — Cliente cadastrado no sistema (Módulo 2).
 */
@Entity
@Table(name = "clients")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 200)
    private String name;

    /** CNPJ ou CPF */
    @Column(length = 20)
    private String document;

    @Column(length = 300)
    private String description;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /** Operações vinculadas ao cliente (N:N via client_operations). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "client_operations",
            joinColumns = @JoinColumn(name = "client_id"),
            inverseJoinColumns = @JoinColumn(name = "operation_id")
    )
    @Builder.Default
    @JsonIgnore
    private Set<Operation> operations = new HashSet<>();

    /**
     * Unidades de Negócio (BU) às quais o cliente pode ser associado — opcional, múltiplo.
     * EAGER (não LAZY): serializado direto pelo Jackson e spring.jpa.open-in-view=false
     * neste projeto — LAZY aqui vira LazyInitializationException fora de transação.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "client_business_units",
            joinColumns = @JoinColumn(name = "client_id"),
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
