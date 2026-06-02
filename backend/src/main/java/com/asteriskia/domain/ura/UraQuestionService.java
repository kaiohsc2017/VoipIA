package com.asteriskia.domain.ura;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * UraQuestionService — Lógica de negócio das perguntas da URA.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UraQuestionService {

    private final UraQuestionRepository repository;

    /** Retorna todas as perguntas ativas, ordenadas pela sequência. */
    @Transactional(readOnly = true)
    public List<UraQuestion> findActiveQuestions() {
        return repository.findByIsActiveTrueOrderByQuestionOrderAsc();
    }

    /** Retorna todas as perguntas (admin). */
    @Transactional(readOnly = true)
    public List<UraQuestion> findAll() {
        return repository.findAll();
    }

    /** Cria ou atualiza uma pergunta. */
    @Transactional
    public UraQuestion save(UraQuestion question) {
        return repository.save(question);
    }

    /** Ativa ou desativa uma pergunta. */
    @Transactional
    public void setActive(Integer id, boolean active) {
        repository.findById(id).ifPresent(q -> {
            q.setIsActive(active);
            repository.save(q);
            log.info("URA Question id={} isActive={}", id, active);
        });
    }

    /** Remove uma pergunta. */
    @Transactional
    public void delete(Integer id) {
        repository.deleteById(id);
    }
}
