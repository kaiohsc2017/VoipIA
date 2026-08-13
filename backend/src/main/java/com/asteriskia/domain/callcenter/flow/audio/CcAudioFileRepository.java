package com.asteriskia.domain.callcenter.flow.audio;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CcAudioFileRepository extends JpaRepository<CcAudioFile, Long> {
    List<CcAudioFile> findAllByOrderByNameAsc();
}
