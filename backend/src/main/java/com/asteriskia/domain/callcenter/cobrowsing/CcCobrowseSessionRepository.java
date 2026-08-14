package com.asteriskia.domain.callcenter.cobrowsing;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CcCobrowseSessionRepository
        extends JpaRepository<CcCobrowseSession, Long>, JpaSpecificationExecutor<CcCobrowseSession> {

    Optional<CcCobrowseSession> findByChatSessionId(Long chatSessionId);

    /**
     * Sessões elegíveis ao expurgo de retenção (Fase 17d): iniciadas antes do corte e ainda não
     * purgadas — nunca reprocessa uma sessão já com {@code purgedAt} preenchido (idempotência).
     */
    List<CcCobrowseSession> findByStartedAtBeforeAndPurgedAtIsNull(LocalDateTime cutoff);
}
