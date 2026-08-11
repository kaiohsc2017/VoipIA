package com.asteriskia.domain.cadastro;

import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.Operadora;
import com.asteriskia.domain.masterdata.Operation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Linha — linha de operadora cadastrada (bloco Cadastros). */
@Entity
@Table(name = "linhas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Linha {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotNull
    @ManyToOne
    @JoinColumn(name = "operadora_id", nullable = false)
    private Operadora operadora;

    @ManyToOne
    @JoinColumn(name = "operation_id")
    private Operation operation;

    @Column(length = 200)
    private String chave;

    @Column(name = "ip_operadora", length = 64)
    private String ipOperadora;

    @Column(name = "ip_autoglass", length = 64)
    private String ipAutoglass;

    @Column(length = 500)
    private String observacao;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * Unidades de Negócio (BU) às quais a linha pode ser associada — opcional, múltiplo. EAGER (não
     * LAZY): serializado direto pelo Jackson e spring.jpa.open-in-view=false neste projeto — LAZY
     * aqui vira LazyInitializationException fora de transação.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "linhas_business_units",
            joinColumns = @JoinColumn(name = "linha_id"),
            inverseJoinColumns = @JoinColumn(name = "business_unit_id"))
    @Builder.Default
    private Set<BusinessUnit> businessUnits = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
