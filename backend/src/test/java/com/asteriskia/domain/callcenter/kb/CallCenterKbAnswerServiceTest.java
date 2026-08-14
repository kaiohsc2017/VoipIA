package com.asteriskia.domain.callcenter.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.ai.AiModelPricingRepository;
import com.asteriskia.domain.ai.AiProviderService;
import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * CallCenterKbAnswerServiceTest — cobre os ramos que decidem "escalar sem chamar o LLM" (o cerne
 * do §25.3 — "sem trecho relevante acima do limiar, nunca inventa resposta"), sem mockar a cadeia
 * fluente do {@link WebClient} (ramo que de fato chama o Gemini) — verboso demais para o retorno,
 * coberto na prática pela validação manual em produção documentada no plano. Os 4 cenários aqui
 * cobrem tudo que decide ANTES de qualquer chamada ao LLM, incluindo a garantia mais importante:
 * nenhuma chamada de custo acontece quando não há base real para responder.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterKbAnswerServiceTest {

    @Mock private CallCenterKbEmbeddingClient embeddingClient;
    @Mock private CallCenterKbChunkDao chunkDao;
    @Mock private CcChatSessionRepository chatSessionRepository;
    @Mock private CcKbAnswerLogRepository answerLogRepository;
    @Mock private AiProviderService aiProviderService;
    @Mock private AiModelPricingRepository pricingRepository;

    private CallCenterKbAnswerService service;

    @BeforeEach
    void setUp() {
        service =
                new CallCenterKbAnswerService(
                        embeddingClient,
                        chunkDao,
                        chatSessionRepository,
                        answerLogRepository,
                        aiProviderService,
                        pricingRepository,
                        new ObjectMapper(),
                        WebClient.builder());
        ReflectionTestUtils.setField(service, "model", "gemini-2.5-flash");
        ReflectionTestUtils.setField(service, "topK", 4);
        ReflectionTestUtils.setField(service, "matchThreshold", 0.55);
    }

    @Test
    @DisplayName("pergunta em branco: escala sem gerar embedding nem consultar chunks")
    void answer_perguntaEmBranco_escalaSemBuscarNaBase() {
        when(chatSessionRepository.findById(1L)).thenReturn(Optional.of(session(1L)));

        var result = service.answer(1L, "   ");

        assertThat(result.matched()).isFalse();
        assertThat(result.answerText()).isNull();
        verify(embeddingClient, never()).embedAsVectorLiteral(any());
        verify(answerLogRepository).save(argThat(matchedFalseCostZero()));
    }

    @Test
    @DisplayName("falha ao gerar embedding da pergunta: escala, nunca lança exceção pro chamador")
    void answer_falhaNoEmbedding_escalaSemLancar() {
        when(embeddingClient.embedAsVectorLiteral("pergunta")).thenThrow(new RuntimeException("timeout"));
        when(chatSessionRepository.findById(2L)).thenReturn(Optional.of(session(2L)));

        var result = service.answer(2L, "pergunta");

        assertThat(result.matched()).isFalse();
        verify(aiProviderService, never()).getRawKey(any());
    }

    @Test
    @DisplayName("nenhum trecho acima do limiar: escala sem nunca chamar o LLM (garantia central do §25.3)")
    void answer_semTrechoRelevante_escalaSemChamarLlm() {
        when(embeddingClient.embedAsVectorLiteral("qual o horário?")).thenReturn("[0.1,0.2]");
        when(chunkDao.searchTopK("[0.1,0.2]", 4))
                .thenReturn(List.of(new CallCenterKbChunkDao.ChunkMatch("trecho pouco relacionado", 0.3, "Artigo X", null)));
        when(chatSessionRepository.findById(3L)).thenReturn(Optional.of(session(3L)));

        var result = service.answer(3L, "qual o horário?");

        assertThat(result.matched()).isFalse();
        verify(aiProviderService, never()).getRawKey(any());
        verify(answerLogRepository).save(argThat(matchedFalseCostZero()));
    }

    @Test
    @DisplayName("trecho acima do limiar mas sem API key do Gemini configurada: escala, nunca chama o modelo")
    void answer_semApiKey_escala() {
        when(embeddingClient.embedAsVectorLiteral("qual o horário?")).thenReturn("[0.1,0.2]");
        when(chunkDao.searchTopK("[0.1,0.2]", 4))
                .thenReturn(List.of(new CallCenterKbChunkDao.ChunkMatch("horário de funcionamento é 8h-18h", 0.9, "Artigo X", null)));
        when(aiProviderService.getRawKey("gemini")).thenReturn("");
        when(chatSessionRepository.findById(4L)).thenReturn(Optional.of(session(4L)));

        var result = service.answer(4L, "qual o horário?");

        assertThat(result.matched()).isFalse();
        verify(answerLogRepository).save(argThat(matchedFalseCostZero()));
    }

    @Test
    @DisplayName("sessão de chat inexistente: nunca tenta salvar o log de resposta")
    void answer_sessaoInexistente_naoSalvaLog() {
        when(chatSessionRepository.findById(99L)).thenReturn(Optional.empty());

        service.answer(99L, "   ");

        verify(answerLogRepository, never()).save(any());
    }

    private static CcChatSession session(Long id) {
        var session = new CcChatSession();
        session.setId(id);
        return session;
    }

    private static org.mockito.ArgumentMatcher<CcKbAnswerLog> matchedFalseCostZero() {
        return log ->
                log != null
                        && !log.getMatched()
                        && log.getAnswer() == null
                        && log.getCostUsd().compareTo(BigDecimal.ZERO) == 0;
    }

    // Import estático usado só pelo ArgumentMatcher acima — evita depender de argThat de
    // Mockito.ArgumentMatchers com tipagem genérica verbosa demais neste arquivo de teste.
    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
