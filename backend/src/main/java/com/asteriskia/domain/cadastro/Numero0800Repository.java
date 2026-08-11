package com.asteriskia.domain.cadastro;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface Numero0800Repository extends JpaRepository<Numero0800, Integer> {
    List<Numero0800> findByIsActive(Boolean isActive);
}
