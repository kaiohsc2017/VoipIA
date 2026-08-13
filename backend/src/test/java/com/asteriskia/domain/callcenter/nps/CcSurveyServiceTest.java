package com.asteriskia.domain.callcenter.nps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** CcSurveyServiceTest — CRUD de pesquisas (Fase 21): escala válida, trava de edição após
 * primeira resposta registrada, nenhum hard delete (só active=false). */
@ExtendWith(MockitoExtension.class)
class CcSurveyServiceTest {

    @Mock private CcSurveyRepository surveyRepository;
    @Mock private CcSurveyQuestionRepository questionRepository;
    @Mock private CcSurveyResponseRepository responseRepository;
    @Mock private BusinessUnitRepository businessUnitRepository;

    private CcSurveyService newService() {
        return new CcSurveyService(surveyRepository, questionRepository, responseRepository, businessUnitRepository);
    }

    private SurveyRequest request() {
        return new SurveyRequest("Pesquisa Teste", SurveyMode.DTMF_SIMPLES, 10, null,
                List.of(new SurveyRequest.QuestionInput(1, "Nota geral?", null)));
    }

    @Test
    @DisplayName("create rejeita escala inválida")
    void create_invalidScale_throws() {
        var service = newService();
        var request = new SurveyRequest("Pesquisa Teste", SurveyMode.DTMF_SIMPLES, 7, null,
                List.of(new SurveyRequest.QuestionInput(1, "Nota?", null)));

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("create persiste a pesquisa e as perguntas")
    void create_valid_persists() {
        var service = newService();
        when(surveyRepository.save(any())).thenAnswer(inv -> {
            CcSurvey s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        when(questionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.create(request());

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.questions()).hasSize(1);
    }

    @Test
    @DisplayName("update rejeita quando a pesquisa já tem resposta registrada")
    void update_withExistingResponses_throws() {
        var service = newService();
        when(surveyRepository.findById(1L)).thenReturn(Optional.of(CcSurvey.builder().id(1L).build()));
        when(responseRepository.existsByQuestion_SurveyId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("já tem resposta");
        verify(questionRepository, org.mockito.Mockito.never()).deleteBySurveyId(any());
    }

    @Test
    @DisplayName("update sem respostas existentes recria as perguntas normalmente")
    void update_withoutResponses_recreatesQuestions() {
        var service = newService();
        var survey = CcSurvey.builder().id(1L).build();
        when(surveyRepository.findById(1L)).thenReturn(Optional.of(survey));
        when(responseRepository.existsByQuestion_SurveyId(1L)).thenReturn(false);
        when(surveyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(1L, request());

        verify(questionRepository).deleteBySurveyId(1L);
        verify(questionRepository).saveAll(any());
    }

    @Test
    @DisplayName("setActive nunca apaga a pesquisa, só alterna o flag")
    void setActive_togglesFlagWithoutDeleting() {
        var service = newService();
        var survey = CcSurvey.builder().id(1L).active(true).build();
        when(surveyRepository.findById(1L)).thenReturn(Optional.of(survey));
        when(surveyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.setActive(1L, false);

        assertThat(dto.active()).isFalse();
        verify(surveyRepository, org.mockito.Mockito.never()).delete(any());
        verify(surveyRepository, org.mockito.Mockito.never()).deleteById(any());
    }

    @Test
    @DisplayName("findById lança 404 para pesquisa inexistente")
    void findById_unknown_throws404() {
        var service = newService();
        when(surveyRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L)).isInstanceOf(ResponseStatusException.class);
    }
}
