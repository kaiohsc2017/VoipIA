package com.asteriskia.domain.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * AsteriskLogClientTest — teste de caracterização (fase 11 da refatoração). Cobre a busca das
 * últimas linhas do log do Asterisk via docker-helper, extraída de SecurityController.
 */
class AsteriskLogClientTest {

    @Mock private RestTemplate restTemplate;

    private AsteriskLogClient client;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        client = new AsteriskLogClient(restTemplate);
        ReflectionTestUtils.setField(client, "dockerHelperUrl", "http://docker-helper:8090");
        ReflectionTestUtils.setField(client, "internalApiKey", "test-key");
    }

    @SuppressWarnings("unchecked")
    @Test
    void tail_devolveAsLinhasDoCorpoDaResposta() {
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("lines", List.of("linha 1", "linha 2"))));

        assertThat(client.tail(200)).containsExactly("linha 1", "linha 2");
    }

    @SuppressWarnings("unchecked")
    @Test
    void tail_corpoNulo_devolveListaVazia() {
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThat(client.tail(200)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void tail_montaUrlComQueryParamLinesEHeaderDeAutenticacaoInterna() {
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("lines", List.of())));

        client.tail(50);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate)
                .exchange(
                        urlCaptor.capture(),
                        eq(HttpMethod.GET),
                        entityCaptor.capture(),
                        eq(Map.class));

        assertThat(urlCaptor.getValue())
                .isEqualTo("http://docker-helper:8090/asterisk/log?lines=50");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Internal-Key"))
                .isEqualTo("test-key");
    }
}
