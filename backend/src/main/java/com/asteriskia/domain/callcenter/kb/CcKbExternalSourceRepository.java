package com.asteriskia.domain.callcenter.kb;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CcKbExternalSourceRepository extends JpaRepository<CcKbExternalSource, Long> {

    List<CcKbExternalSource> findAllByOrderByUrlAsc();

    List<CcKbExternalSource> findByActiveTrue();
}
