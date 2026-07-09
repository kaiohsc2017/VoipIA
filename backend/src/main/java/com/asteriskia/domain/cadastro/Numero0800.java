package com.asteriskia.domain.cadastro;

import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.Client;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Numero0800 — número 0800 cadastrado (bloco Cadastros).
 */
@Entity
@Table(name = "numeros_0800")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Numero0800 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotBlank
    @Column(nullable = false, length = 200)
    private String operadora;

    @NotBlank
    @Column(nullable = false, length = 40)
    private String numero;

    @ManyToOne
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(length = 500)
    private String observacao;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * Unidades de Negócio (BU) às quais o número 0800 pode ser associado —
     * opcional, múltiplo. EAGER (não LAZY): serializado direto pelo Jackson e
     * spring.jpa.open-in-view=false neste projeto — LAZY aqui vira
     * LazyInitializationException fora de transação.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "numeros_0800_business_units",
            joinColumns = @JoinColumn(name = "numero_0800_id"),
            inverseJoinColumns = @JoinColumn(name = "business_unit_id")
    )
    @Builder.Default
    private Set<BusinessUnit> businessUnits = new HashSet<>();

    /**
     * Grupos de regeneração (até 5) do número 0800 — unidirecional, dono da
     * relação. EAGER pelo mesmo motivo das BUs acima.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "numero_0800_id")
    @OrderBy("ordem ASC")
    @Valid
    @Size(max = 5, message = "Máximo de 5 regenerados por número 0800")
    @Builder.Default
    private List<Numero0800Regenerado> regenerados = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
