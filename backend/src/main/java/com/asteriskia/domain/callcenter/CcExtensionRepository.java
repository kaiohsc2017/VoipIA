package com.asteriskia.domain.callcenter;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CcExtensionRepository extends JpaRepository<CcExtension, Long> {
    Optional<CcExtension> findByExtension(String extension);

    Optional<CcExtension> findByAgentId(Long agentId);

    /**
     * Próximo ramal livre em [start, end] (Fase 12.1 — provisionamento automático de atendente).
     * Espelha {@code AppUserRepository.findNextExtension} (mesmo padrão de série gerada + NOT IN),
     * mas com faixa fechada — a de agente (4000-4999) tem limite superior real, diferente da de
     * usuário (9001+, sem teto natural). Retorna {@code null} (nunca lança) quando a faixa está
     * esgotada — o service decide o erro de negócio, o repositório só informa "não achei".
     */
    @Query(value = """
        SELECT s.ext FROM generate_series(:start, :end) AS s(ext)
        WHERE s.ext::text NOT IN (SELECT extension FROM cc_extensions)
        ORDER BY s.ext LIMIT 1
        """, nativeQuery = true)
    Integer findNextExtension(@Param("start") int start, @Param("end") int end);
}
