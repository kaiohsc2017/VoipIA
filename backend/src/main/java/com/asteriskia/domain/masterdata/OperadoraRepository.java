package com.asteriskia.domain.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OperadoraRepository extends JpaRepository<Operadora, Integer> {
    List<Operadora> findByIsActive(Boolean isActive);
}
