package com.asteriskia.domain.cadastro;

import com.asteriskia.domain.masterdata.Operadora;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Numero0800Regenerado — grupo de regeneração (até 5) de um número 0800.
 * Bidirecional (dono da FK): precisa ser o lado dono para que o Hibernate
 * inclua {@code numero_0800_id} já no INSERT — um {@code @OneToMany}
 * unidirecional com {@code @JoinColumn} faz Hibernate inserir a linha sem a
 * FK e só depois rodar um UPDATE para setá-la, o que falha porque a coluna é
 * NOT NULL. Ver {@link Numero0800#setRegenerados}, que mantém esta referência
 * sincronizada.
 */
@Entity
@Table(name = "numero_0800_regenerados")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Numero0800Regenerado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "numero_0800_id", nullable = false)
    @JsonIgnore
    private Numero0800 numero0800;

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

    @ManyToOne
    @JoinColumn(name = "operadora_id")
    private Operadora operadora;
}
