package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CallAudioFileRepository
        extends JpaRepository<CallAudioFile, Long>, JpaSpecificationExecutor<CallAudioFile> {

    Optional<CallAudioFile> findByCallRef(String callRef);

    @Query("SELECT c.callRef FROM CallAudioFile c")
    List<String> findAllCallRefs();
}
