package com.asteriskia.domain.callcenter.chat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcChatMessageRepository extends JpaRepository<CcChatMessage, Long> {

    List<CcChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
