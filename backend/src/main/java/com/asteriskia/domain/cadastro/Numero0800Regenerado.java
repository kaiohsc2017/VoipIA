package com.asteriskia.domain.cadastro;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Numero0800Regenerado — grupo de regeneração (até 5) de um número 0800.
 * Unidirecional: o dono da relação é {@link Numero0800#getRegenerados()}, esta
 * entidade não referencia o pai de volta.
 */
@Entity
@Table(name = "numero_0800_regenerados")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Numero0800Regenerado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotNull
    @Min(1)
    @Max(5)
    @Column(nullable = false)
    private Integer ordem;

    @Column(name = "numero_regenerado", length = 40)
    private String numeroRegenerado;

    @Column(length = 40)
    private String vdn;

    @Column(length = 100)
    private String vetor;

    @Column(length = 100)
    private String operadora;
}
