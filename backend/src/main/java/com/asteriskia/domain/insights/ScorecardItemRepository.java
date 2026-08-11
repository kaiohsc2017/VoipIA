package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScorecardItemRepository extends JpaRepository<ScorecardItem, Long> {

    List<ScorecardItem> findByScorecardIdOrderByOrdemAsc(Long scorecardId);

    void deleteByScorecardId(Long scorecardId);
}
