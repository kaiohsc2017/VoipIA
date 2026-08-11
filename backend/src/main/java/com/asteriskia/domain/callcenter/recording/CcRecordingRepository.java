package com.asteriskia.domain.callcenter.recording;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CcRecordingRepository
        extends JpaRepository<CcRecording, Long>, JpaSpecificationExecutor<CcRecording> {

    Optional<CcRecording> findByChannelUniqueId(String channelUniqueId);

    /** Usado pela retenção — teto exclusivo (fronteira exatamente no limite NÃO purga). */
    List<CcRecording> findByStartedAtBefore(LocalDateTime cutoff);
}
