package com.asteriskia.domain.callcenter.ia;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CcIaAgentRepository extends JpaRepository<CcIaAgent, Long> {

    List<CcIaAgent> findAllByOrderByNameAsc();

    List<CcIaAgent> findByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
