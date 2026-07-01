package com.asteriskia.domain.ura;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * UraQuestionRepository — Acesso a dados das perguntas de cada URA.
 */
@Repository
public interface UraQuestionRepository extends JpaRepository<UraQuestion, Integer> {

    /** Retorna perguntas ativas de uma URA, ordenadas pela ordem de exibição. */
    List<UraQuestion> findByUraIdAndIsActiveTrueOrderByQuestionOrderAsc(Integer uraId);

    /** Retorna todas as perguntas de uma URA (admin). */
    List<UraQuestion> findByUraIdOrderByQuestionOrderAsc(Integer uraId);

    /** Usado para mapear uma resposta (jiraFieldKey) de volta à pergunta que a originou. */
    Optional<UraQuestion> findByUraIdAndJiraFieldKey(Integer uraId, String jiraFieldKey);
}
