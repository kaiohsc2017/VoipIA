package com.asteriskia.domain.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OperationRepository extends JpaRepository<Operation, Integer> {
    List<Operation> findByIsActive(Boolean isActive);
}
