package com.asteriskia.domain.ura;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * UraQuestionRepository — Acesso a dados das perguntas da URA.
 */
@Repository
public interface UraQuestionRepository extends JpaRepository<UraQuestion, Integer> {

    /** Retorna perguntas ativas ordenadas pela ordem de exibição. */
    List<UraQuestion> findByIsActiveTrueOrderByQuestionOrderAsc();
}
