package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.callcenter.cobrowsing.CcCobrowseSession;
import com.asteriskia.domain.callcenter.cobrowsing.CobrowseConsentService;
import com.asteriskia.domain.callcenter.cobrowsing.CobrowseIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre o endpoint {@code POST /chat/public/sessions/{id}/cobrowse-consent} (Fase 17a) — mesma
 * validação manual de token do resto do controller (nunca aceita JWT de staff, só o token
 * {@code chat_customer} desta sessão específica) + rate limit dedicado.
 */
@ExtendWith(MockitoExtension.class)
class PublicCallCenterChatControllerCobrowseConsentTest {

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
    @Mock
    private ChatAttachmentService attachmentService;

    private PublicCallCenterChatController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicCallCenterChatController(
                chatService, rateLimiter, jwtService, cobrowseConsentService, cobrowseIngestService, new ObjectMapper(), attachmentService);
    }

    private PublicCallCenterChatController.CobrowseConsentRequest requestOf(boolean granted) {
        return new PublicCallCenterChatController.CobrowseConsentRequest(granted, "0123456789abcdef");
    }

    @Test
    @DisplayName("200 com token válido da própria sessão — delega ao serviço de consentimento")
    void cobrowseConsent_validToken_delegatesToService() {
        when(jwtService.validateChatCustomerToken("tok-valido", 5L)).thenReturn(true);
        when(rateLimiter.allowCobrowseConsent(5L)).thenReturn(true);
        CcCobrowseSession expected = CcCobrowseSession.builder().id(1L).chatSessionId(5L).consentStatus("granted").build();
        when(cobrowseConsentService.registerConsent(5L, true, "0123456789abcdef")).thenReturn(expected);

        CcCobrowseSession result = controller.cobrowseConsent(5L, requestOf(true), "Bearer tok-valido");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("401 com token de outra sessão (validateChatCustomerToken nega)")
    void cobrowseConsent_tokenOfAnotherSession_throws401() {
        when(jwtService.validateChatCustomerToken("tok-de-outra-sessao", 5L)).thenReturn(false);

        assertThatThrownBy(() -> controller.cobrowseConsent(5L, requestOf(true), "Bearer tok-de-outra-sessao"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inválido");

        verify(cobrowseConsentService, never()).registerConsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("401/403 sem header Authorization — nunca aceita JWT de staff nem requisição anônima")
    void cobrowseConsent_missingAuthorizationHeader_throws401() {
        assertThatThrownBy(() -> controller.cobrowseConsent(5L, requestOf(true), "NaoEhBearer xyz"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inválido");

        verify(jwtService, never()).validateChatCustomerToken(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(cobrowseConsentService, never()).registerConsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("429 quando o rate limit dedicado do endpoint é excedido")
    void cobrowseConsent_rateLimited_throws429() {
        when(jwtService.validateChatCustomerToken("tok-valido", 5L)).thenReturn(true);
        when(rateLimiter.allowCobrowseConsent(5L)).thenReturn(false);

        assertThatThrownBy(() -> controller.cobrowseConsent(5L, requestOf(true), "Bearer tok-valido"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Muitas requisições");

        verify(cobrowseConsentService, never()).registerConsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any());
    }
}
