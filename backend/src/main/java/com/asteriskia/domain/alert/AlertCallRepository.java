package com.asteriskia.domain.alert;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertCallRepository extends JpaRepository<AlertCall, Long> {
    Optional<AlertCall> findByAsteriskCallId(String asteriskCallId);
    boolean existsByZabbixTriggerIdAndCallStatusIn(String triggerId, List<String> statuses);
    Page<AlertCall> findAllByOrderByCallDateDesc(Pageable pageable);
}
