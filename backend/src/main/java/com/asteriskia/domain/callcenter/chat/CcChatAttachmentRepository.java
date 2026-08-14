package com.asteriskia.domain.callcenter.chat;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CcChatAttachmentRepository extends JpaRepository<CcChatAttachment, Long> {

    List<CcChatAttachment> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    /** Cota (Fase 7d) — soma do tamanho de tudo que este uploader enviou dentro da janela de
     * retenção configurada no canal; usado para decidir se um novo upload cabe na cota. */
    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM CcChatAttachment a "
            + "WHERE a.uploaderKey = :uploaderKey AND a.createdAt >= :since")
    long sumSizeByUploaderSince(@Param("uploaderKey") String uploaderKey, @Param("since") LocalDateTime since);

    /** Expurgo noturno (Fase 7d) — anexos mais velhos que a retenção configurada no canal. */
    @Query("SELECT a FROM CcChatAttachment a JOIN CcChatSession s ON s.id = a.sessionId "
            + "WHERE s.channel.id = :channelId AND a.createdAt < :cutoff")
    List<CcChatAttachment> findExpiredForChannel(@Param("channelId") Long channelId, @Param("cutoff") LocalDateTime cutoff);
}
