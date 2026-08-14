package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.callcenter.cobrowsing.CobrowseConsentService;
import com.asteriskia.domain.callcenter.cobrowsing.CobrowseIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre o endpoint {@code POST /chat/public/sessions/{id}/cobrowse-events} (Fase 17b) — as
 * guardas de negócio (consentimento/sessão encerrada/toggle do agente/teto acumulado) são
 * responsabilidade de {@link CobrowseIngestService} (coberto em teste próprio); aqui só o
 * contrato do controller: token, teto de corpo (413) e rate limit (429).
 */
@ExtendWith(MockitoExtension.class)
class PublicCallCenterChatControllerCobrowseEventsTest {

    @Mock
    private CcChatService chatService;
    @Mock
    private PublicChatRateLimiter rateLimiter;
    @Mock
    private JwtService jwtService;
    @Mock
    private CobrowseConsentService cobrowseConsentService;
    @Mock
    private CobrowseIngestService cobrowseIngestService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PublicCallCenterChatController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicCallCenterChatController(
                chatService, rateLimiter, jwtService, cobrowseConsentService, cobrowseIngestService, objectMapper);
    }

    private byte[] validBody() {
        return "{\"seq\":1,\"events\":[{\"type\":2,\"data\":{}}]}".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("204 com token válido, corpo dentro do teto e rate limit ok — delega ao serviço de ingestão")
    void cobrowseEvents_valid_returns204AndDelegates() {
        when(jwtService.validateChatCustomerToken("tok-valido", 5L)).thenReturn(true);
        when(rateLimiter.allowCobrowseEvents(5L)).thenReturn(true);

        ResponseEntity<Void> result = controller.cobrowseEvents(5L, validBody(), "Bearer tok-valido");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cobrowseIngestService).ingest(ArgumentMatchers.eq(5L), ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("401 com token inválido — nunca chega a checar tamanho/rate limit/ingestão")
    void cobrowseEvents_invalidToken_throws401() {
        when(jwtService.validateChatCustomerToken("tok-invalido", 5L)).thenReturn(false);

        assertThatThrownBy(() -> controller.cobrowseEvents(5L, validBody(), "Bearer tok-invalido"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inválido");

        verify(rateLimiter, never()).allowCobrowseEvents(ArgumentMatchers.any());
        verify(cobrowseIngestService, never()).ingest(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("413 quando o corpo excede 512KB — nunca desserializa nem chega ao rate limit")
    void cobrowseEvents_bodyTooLarge_throws413() {
        when(jwtService.validateChatCustomerToken("tok-valido", 5L)).thenReturn(true);
        byte[] tooLarge = new byte[512 * 1024 + 1];

        assertThatThrownBy(() -> controller.cobrowseEvents(5L, tooLarge, "Bearer tok-valido"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        verify(rateLimiter, never()).allowCobrowseEvents(ArgumentMatchers.any());
        verify(cobrowseIngestService, never()).ingest(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("429 quando o rate limit dedicado do endpoint é excedido")
    void cobrowseEvents_rateLimited_throws429() {
        when(jwtService.validateChatCustomerToken("tok-valido", 5L)).thenReturn(true);
        when(rateLimiter.allowCobrowseEvents(5L)).thenReturn(false);

        assertThatThrownBy(() -> controller.cobrowseEvents(5L, validBody(), "Bearer tok-valido"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Muitas requisições");

        verify(cobrowseIngestService, never()).ingest(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("400 quando o corpo não é um JSON válido")
    void cobrowseEvents_invalidJson_throws400() {
        when(jwtService.validateChatCustomerToken("tok-valido", 5L)).thenReturn(true);
        when(rateLimiter.allowCobrowseEvents(5L)).thenReturn(true);
        byte[] malformed = "{ isso nao é json".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> controller.cobrowseEvents(5L, malformed, "Bearer tok-valido"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(cobrowseIngestService, never()).ingest(ArgumentMatchers.any(), ArgumentMatchers.any());
    }
}
