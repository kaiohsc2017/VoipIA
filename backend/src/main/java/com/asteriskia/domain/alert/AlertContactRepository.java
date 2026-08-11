package com.asteriskia.domain.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertContactRepository extends JpaRepository<AlertContact, Integer> {
    List<AlertContact> findByIsActiveTrueOrderByPriorityOrderAsc();
    List<AlertContact> findByIsActiveTrueAndOperationIdOrderByPriorityOrderAsc(Long operationId);
}
