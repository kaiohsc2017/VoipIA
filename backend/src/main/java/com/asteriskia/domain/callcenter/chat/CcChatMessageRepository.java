package com.asteriskia.domain.callcenter.chat;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcChatMessageRepository extends JpaRepository<CcChatMessage, Long> {

    List<CcChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    /** Agregado diário de chat (Fase 9c.2) — mensagens de um lote de sessões, filtradas por
     * {@code createdAt} (coluna de partição da V71) para habilitar pruning. O service passa a
     * janela [menor {@code startedAt}, maior {@code closedAt} do lote, com folga], nunca "todas
     * as mensagens dessas sessões" sem limite de data. */
    List<CcChatMessage> findBySessionIdInAndCreatedAtBetweenOrderByCreatedAtAsc(
            List<Long> sessionIds, LocalDateTime from, LocalDateTime to);
}
