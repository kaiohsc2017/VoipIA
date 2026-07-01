package com.asteriskia.domain.call;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * UraAnswer — Uma resposta a uma pergunta específica da URA numa chamada.
 *
 * Uma linha por resposta, em vez de uma coluna por pergunta — criar, editar
 * ou remover uma pergunta na tela de Fluxo URA nunca exige alterar o schema
 * do banco (ALTER TABLE). O relatório monta as "colunas por pergunta"
 * juntando estas linhas com o texto da pergunta em ura_questions.
 */
@Entity
@Table(name = "ura_answers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UraAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_record_id", nullable = false)
    private Long callRecordId;

    @Column(name = "ura_question_id", nullable = false)
    private Integer uraQuestionId;

    @Column(columnDefinition = "TEXT")
    private String value;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
