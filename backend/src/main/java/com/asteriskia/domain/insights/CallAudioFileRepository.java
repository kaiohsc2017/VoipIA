package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CallAudioFileRepository
        extends JpaRepository<CallAudioFile, Long>, JpaSpecificationExecutor<CallAudioFile> {

    Optional<CallAudioFile> findByCallRef(String callRef);

    @Query("SELECT new com.asteriskia.domain.insights.CallStatusRef(c.callRef, c.status) FROM CallAudioFile c")
    List<CallStatusRef> findAllRefsAndStatus();

    /** Posição na fila (FIFO por ordem de descoberta) — só tem sentido pra status='pending';
     * conta quantas linhas pendentes foram descobertas antes desta. */
    @Query("SELECT COUNT(c) FROM CallAudioFile c WHERE c.status = 'pending' AND c.ingestedAt < :ingestedAt")
    long countPendingBefore(@Param("ingestedAt") java.time.LocalDateTime ingestedAt);
}
