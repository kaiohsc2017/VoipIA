package com.asteriskia.domain.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BusinessUnitRepository extends JpaRepository<BusinessUnit, Integer> {
    List<BusinessUnit> findByIsActive(Boolean isActive);
}
