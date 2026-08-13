package com.asteriskia.domain.callcenter.nps;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CcSurveyResponseRepository extends JpaRepository<CcSurveyResponse, Long> {

    /** Custo total de transcrição/classificação de NPS falada no período — frente
     * {@code callcenter_nps} do Financeiro (Fase 21, §21.5). Só respostas FALADA_IA geram
     * {@code aiCostUsd}; DTMF nunca chama IA. */
    @Query(
            "select coalesce(sum(r.aiCostUsd), 0) from CcSurveyResponse r "
                    + "where r.aiCostUsd is not null and r.createdAt between :from and :to")
    BigDecimal sumAiCostUsdBetween(LocalDateTime from, LocalDateTime to);
    List<CcSurveyResponse> findByInteractionId(Long interactionId);

    /** Respostas FALADA_IA aguardando transcrição/classificação assíncrona — nunca inclui
     * DTMF_COMENTARIO (transcrição só sob demanda, D21, não pega esta fila). */
    List<CcSurveyResponse> findByAudioPathIsNotNullAndTranscriptIsNullAndQuestion_Survey_Mode(
            SurveyMode mode);

    /** Trava a edição de perguntas de uma pesquisa que já tem resposta registrada — escalas e
     * enunciados diferentes num mesmo histórico de pesquisa produziriam NPS incomparável. */
    boolean existsByQuestion_SurveyId(Long surveyId);
}
