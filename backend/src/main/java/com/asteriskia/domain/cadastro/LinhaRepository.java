package com.asteriskia.domain.cadastro;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface LinhaRepository extends JpaRepository<Linha, Integer> {
    List<Linha> findByIsActive(Boolean isActive);
}
