package com.asteriskia.domain.connectivity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface NumberTestRepository extends JpaRepository<NumberTest, Long> {
    List<NumberTest> findByIsActive(Boolean isActive);

    /** Controle de acesso por BU — usado quando o usuário logado é restrito a BUs específicas. */
    List<NumberTest> findByIsActiveAndBusinessUnit_IdIn(Boolean isActive, Collection<Integer> businessUnitIds);

    List<NumberTest> findByBusinessUnit_IdIn(Collection<Integer> businessUnitIds);
}
