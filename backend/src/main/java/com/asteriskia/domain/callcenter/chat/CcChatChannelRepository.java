package com.asteriskia.domain.callcenter.chat;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcChatChannelRepository extends JpaRepository<CcChatChannel, Long> {

    Optional<CcChatChannel> findByCodeAndActiveTrue(String code);
}
