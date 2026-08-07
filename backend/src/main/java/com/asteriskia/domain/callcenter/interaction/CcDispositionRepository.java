package com.asteriskia.domain.callcenter.interaction;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcDispositionRepository extends JpaRepository<CcDisposition, Long> {
    List<CcDisposition> findByActiveTrue();
}
