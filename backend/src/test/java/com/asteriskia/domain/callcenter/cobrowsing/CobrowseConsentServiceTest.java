package com.asteriskia.domain.callcenter.cobrowsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.masterdata.BusinessUnit;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre o ciclo de vida do consentimento de co-browsing gravado do chat (Fase 17, sub-fase 17a
 * — só o estado; sem captura real, sem arquivo físico a apagar ainda).
 */
@ExtendWith(MockitoExtension.class)
class CobrowseConsentServiceTest {

    @Mock
    private CcCobrowseSessionRepository repository;

    private CobrowseConsentService service;

    private CobrowseConsentService newService() {
        return new CobrowseConsentService(repository);
    }

    private CcAgent agentOf(Long id, boolean cobrowseEnabled) {
        CcAgent agent = new CcAgent();
        agent.setId(id);
        agent.setCobrowseEnabled(cobrowseEnabled);
        return agent;
    }

    private CcChatSession chatSessionOf(Long id, Long businessUnitId) {
        CcChatSession.CcChatSessionBuilder builder = CcChatSession.builder().id(id);
        if (businessUnitId != null) {
            BusinessUnit bu = new BusinessUnit();
            bu.setId(businessUnitId.intValue());
            builder.businessUnit(bu);
        }
        return builder.build();
    }

    @Test
    @DisplayName("ensureSessionForClaim não cria nada se o agente não tem o toggle ligado")
    void ensureSessionForClaim_toggleOff_doesNothing() {
        service = newService();
        CcAgent agent = agentOf(1L, false);
        CcChatSession session = chatSessionOf(5L, 2L);

        service.ensureSessionForClaim(session, agent);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("ensureSessionForClaim cria a sessão de cobrowse quando o agente tem o toggle ligado")
    void ensureSessionForClaim_toggleOn_createsSession() {
        service = newService();
        CcAgent agent = agentOf(1L, true);
        CcChatSession session = chatSessionOf(5L, 2L);
        when(repository.findByChatSessionId(5L)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ensureSessionForClaim(session, agent);

        var captor = org.mockito.ArgumentCaptor.forClass(CcCobrowseSession.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChatSessionId()).isEqualTo(5L);
        assertThat(captor.getValue().getBusinessUnitId()).isEqualTo(2L);
        assertThat(captor.getValue().getConsentStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("ensureSessionForClaim é idempotente — não duplica se já existir sessão para o chat")
    void ensureSessionForClaim_alreadyExists_doesNotDuplicate() {
        service = newService();
        CcAgent agent = agentOf(1L, true);
        CcChatSession session = chatSessionOf(5L, 2L);
        when(repository.findByChatSessionId(5L))
                .thenReturn(Optional.of(CcCobrowseSession.builder().id(9L).chatSessionId(5L).build()));

        service.ensureSessionForClaim(session, agent);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("registerConsent(granted=true) registra hash e timestamp de aceite")
    void registerConsent_granted_recordsHashAndTimestamp() {
        service = newService();
        CcCobrowseSession existing = CcCobrowseSession.builder().id(9L).chatSessionId(5L).consentStatus("pending").build();
        when(repository.findByChatSessionId(5L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcCobrowseSession result = service.registerConsent(5L, true, "abc123");

        assertThat(result.getConsentStatus()).isEqualTo("granted");
        assertThat(result.getConsentTextHash()).isEqualTo("abc123");
        assertThat(result.getConsentAt()).isNotNull();
    }

    @Test
    @DisplayName("registerConsent(granted=false) marca denied quando ainda não havia aceite")
    void registerConsent_denied_marksDenied() {
        service = newService();
        CcCobrowseSession existing = CcCobrowseSession.builder().id(9L).chatSessionId(5L).consentStatus("pending").build();
        when(repository.findByChatSessionId(5L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcCobrowseSession result = service.registerConsent(5L, false, "abc123");

        assertThat(result.getConsentStatus()).isEqualTo("denied");
        assertThat(result.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("registerConsent(granted=false) depois de já ter aceitado vira revogação (revoked_at/purged_at)")
    void registerConsent_deniedAfterGranted_becomesRevoked() {
        service = newService();
        CcCobrowseSession existing = CcCobrowseSession.builder().id(9L).chatSessionId(5L).consentStatus("granted").build();
        when(repository.findByChatSessionId(5L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcCobrowseSession result = service.registerConsent(5L, false, "abc123");

        assertThat(result.getConsentStatus()).isEqualTo("revoked");
        assertThat(result.getRevokedAt()).isNotNull();
        assertThat(result.getPurgedAt()).isNotNull();
    }

    @Test
    @DisplayName("registerConsent rejeita se o consentimento já foi revogado antes")
    void registerConsent_alreadyRevoked_throwsConflict() {
        service = newService();
        CcCobrowseSession existing = CcCobrowseSession.builder().id(9L).chatSessionId(5L).consentStatus("revoked").build();
        when(repository.findByChatSessionId(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.registerConsent(5L, true, "abc123"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("já foi revogado");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("registerConsent responde 404 (nunca 403) quando não existe sessão de cobrowse pra este chat")
    void registerConsent_noSession_throws404() {
        service = newService();
        when(repository.findByChatSessionId(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerConsent(5L, true, "abc123"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não encontrada");
    }

    @Test
    @DisplayName("revoke marca revoked_at/purged_at")
    void revoke_marksRevokedAndPurged() {
        service = newService();
        CcCobrowseSession existing = CcCobrowseSession.builder().id(9L).chatSessionId(5L).consentStatus("granted").build();
        when(repository.findByChatSessionId(5L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcCobrowseSession result = service.revoke(5L);

        assertThat(result.getConsentStatus()).isEqualTo("revoked");
        assertThat(result.getRevokedAt()).isNotNull();
        assertThat(result.getPurgedAt()).isNotNull();
    }

    @Test
    @DisplayName("revoke é idempotente — chamar duas vezes não sobrescreve o timestamp da primeira revogação")
    void revoke_isIdempotent() {
        service = newService();
        CcCobrowseSession existing = CcCobrowseSession.builder().id(9L).chatSessionId(5L).consentStatus("revoked")
                .revokedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        when(repository.findByChatSessionId(5L)).thenReturn(Optional.of(existing));

        CcCobrowseSession result = service.revoke(5L);

        assertThat(result.getRevokedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("consentimento de uma sessão não vaza para outra — cada chatSessionId é isolado")
    void registerConsent_differentSessions_areIsolated() {
        service = newService();
        CcCobrowseSession sessionA = CcCobrowseSession.builder().id(9L).chatSessionId(5L).consentStatus("pending").build();
        CcCobrowseSession sessionB = CcCobrowseSession.builder().id(10L).chatSessionId(6L).consentStatus("pending").build();
        when(repository.findByChatSessionId(5L)).thenReturn(Optional.of(sessionA));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerConsent(5L, true, "hash-a");

        assertThat(sessionA.getConsentStatus()).isEqualTo("granted");
        assertThat(sessionB.getConsentStatus()).isEqualTo("pending");
    }
}
