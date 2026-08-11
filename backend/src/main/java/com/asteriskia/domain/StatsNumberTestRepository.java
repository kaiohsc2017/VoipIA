package com.asteriskia.domain;

import com.asteriskia.domain.connectivity.NumberTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StatsNumberTestRepository extends JpaRepository<NumberTest, Long> {
    long countByIsActiveTrue();
}
