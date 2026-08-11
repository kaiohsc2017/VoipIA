package com.asteriskia.domain.call;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * UraAnswerRepository — Acesso a dados das respostas por pergunta da URA.
 */
@Repository
public interface UraAnswerRepository extends JpaRepository<UraAnswer, Long> {
    List<UraAnswer> findByCallRecordId(Long callRecordId);
    List<UraAnswer> findByCallRecordIdIn(List<Long> callRecordIds);
}
