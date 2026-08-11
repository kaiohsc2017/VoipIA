package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QualityScorecardRepository extends JpaRepository<QualityScorecard, Long> {

    Optional<QualityScorecard> findByIsActiveTrue();
}
