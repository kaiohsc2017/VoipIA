package com.asteriskia.domain.callcenter.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcTelegramPollStateRepository extends JpaRepository<CcTelegramPollState, Long> {
}
