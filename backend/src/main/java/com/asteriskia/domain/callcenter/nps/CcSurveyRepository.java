package com.asteriskia.domain.callcenter.nps;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcSurveyRepository extends JpaRepository<CcSurvey, Long> {
    List<CcSurvey> findByActiveTrue();
}
