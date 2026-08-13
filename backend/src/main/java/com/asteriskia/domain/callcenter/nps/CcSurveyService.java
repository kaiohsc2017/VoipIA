package com.asteriskia.domain.callcenter.nps;

import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CcSurveyService — CRUD de pesquisas de satisfação (Fase 21, D17). Uma pesquisa que já tem
 * resposta registrada não pode mais ter suas perguntas editadas — escala/enunciado diferentes
 * num mesmo histórico produziriam NPS incomparável (mesmo raciocínio do plano para a escala,
 * §21.1). Nenhum hard delete: {@code active=false} é o único jeito de "remover" uma pesquisa em
 * uso (mesmo padrão de {@code CcQueue.active}/{@code CcAgent.active}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcSurveyService {

    private final CcSurveyRepository surveyRepository;
    private final CcSurveyQuestionRepository questionRepository;
    private final CcSurveyResponseRepository responseRepository;
    private final BusinessUnitRepository businessUnitRepository;

    @Transactional(readOnly = true)
    public List<SurveyDto> findAll() {
        return surveyRepository.findAll().stream()
                .map(s -> SurveyDto.from(s, questionRepository.findBySurveyIdOrderByOrderIndexAsc(s.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SurveyDto findById(Long id) {
        var survey = requireSurvey(id);
        return SurveyDto.from(survey, questionRepository.findBySurveyIdOrderByOrderIndexAsc(id));
    }

    @Transactional
    public SurveyDto create(SurveyRequest request) {
        validateScale(request.scaleMax());
        var survey =
                surveyRepository.save(
                        CcSurvey.builder()
                                .name(request.name())
                                .mode(request.mode())
                                .scaleMax(request.scaleMax())
                                .active(true)
                                .businessUnit(resolveBusinessUnit(request.businessUnitId()))
                                .build());
        var questions = saveQuestions(survey, request.questions());
        log.info("Pesquisa de satisfação criada: id={} mode={}", survey.getId(), survey.getMode());
        return SurveyDto.from(survey, questions);
    }

    @Transactional
    public SurveyDto update(Long id, SurveyRequest request) {
        validateScale(request.scaleMax());
        var survey = requireSurvey(id);
        if (responseRepository.existsByQuestion_SurveyId(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta pesquisa já tem resposta registrada — crie uma nova pesquisa para "
                            + "mudar perguntas/escala/modo, em vez de editar esta.");
        }
        survey.setName(request.name());
        survey.setMode(request.mode());
        survey.setScaleMax(request.scaleMax());
        survey.setBusinessUnit(resolveBusinessUnit(request.businessUnitId()));
        surveyRepository.save(survey);
        questionRepository.deleteBySurveyId(id);
        var questions = saveQuestions(survey, request.questions());
        log.info("Pesquisa de satisfação atualizada: id={}", id);
        return SurveyDto.from(survey, questions);
    }

    @Transactional
    public SurveyDto setActive(Long id, boolean active) {
        var survey = requireSurvey(id);
        survey.setActive(active);
        surveyRepository.save(survey);
        return SurveyDto.from(survey, questionRepository.findBySurveyIdOrderByOrderIndexAsc(id));
    }

    private List<CcSurveyQuestion> saveQuestions(CcSurvey survey, List<SurveyRequest.QuestionInput> inputs) {
        var entities =
                inputs.stream()
                        .map(
                                q ->
                                        CcSurveyQuestion.builder()
                                                .survey(survey)
                                                .orderIndex(q.orderIndex())
                                                .text(q.text())
                                                .audioPath(q.audioPath())
                                                .build())
                        .toList();
        return questionRepository.saveAll(entities);
    }

    private void validateScale(Integer scaleMax) {
        if (scaleMax == null || (scaleMax != 5 && scaleMax != 10)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Escala inválida — use 5 (0-5) ou 10 (0-9 mais * = 10).");
        }
    }

    private CcSurvey requireSurvey(Long id) {
        return surveyRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pesquisa não encontrada: " + id));
    }

    private com.asteriskia.domain.masterdata.BusinessUnit resolveBusinessUnit(Integer businessUnitId) {
        if (businessUnitId == null) {
            return null;
        }
        return businessUnitRepository
                .findById(businessUnitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "BU inválida: " + businessUnitId));
    }
}
