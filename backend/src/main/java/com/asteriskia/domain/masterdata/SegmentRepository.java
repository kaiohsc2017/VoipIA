package com.asteriskia.domain.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SegmentRepository extends JpaRepository<Segment, Integer> {
    List<Segment> findByIsActive(Boolean isActive);
}
