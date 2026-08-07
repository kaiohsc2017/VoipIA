package com.asteriskia.domain.callcenter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcPauseReasonRepository extends JpaRepository<CcPauseReason, Long> {
    List<CcPauseReason> findByActiveTrue();

    Optional<CcPauseReason> findByIdAndActiveTrue(Long id);
}
