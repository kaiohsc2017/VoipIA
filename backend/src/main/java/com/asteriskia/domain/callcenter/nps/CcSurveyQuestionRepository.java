package com.asteriskia.domain.callcenter.nps;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcSurveyQuestionRepository extends JpaRepository<CcSurveyQuestion, Long> {
    List<CcSurveyQuestion> findBySurveyIdOrderByOrderIndexAsc(Long surveyId);

    void deleteBySurveyId(Long surveyId);
}
