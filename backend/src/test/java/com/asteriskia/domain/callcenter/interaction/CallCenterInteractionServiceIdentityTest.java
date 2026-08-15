package com.asteriskia.domain.callcenter.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.copilot.CallCenterContactHistoryService;
import com.asteriskia.domain.callcenter.copilot.CcContactProfile;
import com.asteriskia.domain.callcenter.copilot.ContactHistoryItem;
import com.asteriskia.domain.callcenter.copilot.CcContactProfileFeedbackRepository;
import com.asteriskia.domain.callcenter.copilot.CcContactProfileRepository;
import com.asteriskia.domain.callcenter.copilot.ContactProfileService;
import com.asteriskia.domain.callcenter.copilot.ContactProfileView;
import com.asteriskia.integration.ad.AdUser;
import com.asteriskia.integration.ad.AdUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterInteractionServiceIdentityTest — cobre a resolução do bloco de identidade (Fase 14)
 * em {@code currentInteraction()}/{@code contactHistory()}, e o copiloto de IA (Fase 16.2/16.3)
 * em {@code contactProfile()}/{@code submitContactProfileFeedback()}, ambos adicionados sobre o
 * comportamento pré-existente da Fase 4.
 */
class CallCenterInteractionServiceIdentityTest {

    private CcInteractionRepository interactionRepository;
    private CcDispositionRepository dispositionRepository;
    private CallCenterAgentStateService agentStateService;
    private AdUserRepository adUserRepository;
    private ContactProfileService contactProfileService;
    private CcContactProfileRepository contactProfileRepository;
    private CcContactProfileFeedbackRepository contactProfileFeedbackRepository;
    private CallCenterContactHistoryService contactHistoryService;
    private CallCenterInteractionService service;

    @BeforeEach
    void setUp() {
        interactionRepository = mock(CcInteractionRepository.class);
        dispositionRepository = mock(CcDispositionRepository.class);
        agentStateService = mock(CallCenterAgentStateService.class);
        adUserRepository = mock(AdUserRepository.class);
        contactProfileService = mock(ContactProfileService.class);
        contactProfileRepository = mock(CcContactProfileRepository.class);
        contactProfileFeedbackRepository = mock(CcContactProfileFeedbackRepository.class);
        contactHistoryService = mock(CallCenterContactHistoryService.class);
        service = new CallCenterInteractionService(
                interactionRepository, dispositionRepository, agentStateService, adUserRepository,
                contactProfileService, contactProfileRepository, contactProfileFeedbackRepository,
                contactHistoryService);
    }

    @Test
    @DisplayName("interação sem resolved_ad_sam retorna identity=null sem consultar o AD")
    void currentInteraction_withoutResolvedSam_doesNotQueryAd() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var interaction = CcInteraction.builder().id(10L).build();
        when(interactionRepository.findByAgentIdAndEndedAtIsNull(1L)).thenReturn(Optional.of(interaction));

        var view = service.currentInteraction();

        assertThat(view.identity()).isNull();
        verifyNoInteractions(adUserRepository);
    }

    @Test
    @DisplayName("interação com resolved_ad_sam popula o bloco de identidade a partir do ad_users local")
    void currentInteraction_withResolvedSam_populatesIdentity() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var interaction = CcInteraction.builder().id(10L).resolvedAdSam("jsilva").identitySource("URA_INPUT").build();
        when(interactionRepository.findByAgentIdAndEndedAtIsNull(1L)).thenReturn(Optional.of(interaction));
        var adUser = AdUser.builder().samAccountName("jsilva").displayName("João Silva").department("TI").build();
        when(adUserRepository.findBySamAccountNameIgnoreCase("jsilva")).thenReturn(Optional.of(adUser));

        var view = service.currentInteraction();

        assertThat(view.identity()).isNotNull();
        assertThat(view.identity().displayName()).isEqualTo("João Silva");
        assertThat(view.identity().department()).isEqualTo("TI");
        assertThat(view.identity().source()).isEqualTo("URA_INPUT");
    }

    @Test
    @DisplayName("contactHistory sem sam resolvido na própria interação retorna lista vazia sem consultar o repositório")
    void contactHistory_blankSam_returnsEmpty() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var current = CcInteraction.builder().id(10L).agent(agent).build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));

        var result = service.contactHistory(10L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("contactHistory de interação de outro agente retorna lista vazia (nunca usa o sam informado pelo chamador)")
    void contactHistory_interactionOfAnotherAgent_returnsEmpty() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var otherAgent = CcAgent.builder().id(2L).build();
        var current = CcInteraction.builder().id(10L).agent(otherAgent).resolvedAdSam("jsilva").build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));

        var result = service.contactHistory(10L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("contactHistory delega ao repositório usando o sam resolvido da própria interação, excluindo-a")
    void contactHistory_delegatesToRepository() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var current = CcInteraction.builder().id(10L).agent(agent).resolvedAdSam("jsilva").build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));
        var past = CcInteraction.builder().id(5L).resolvedAdSam("jsilva").build();
        when(interactionRepository.findTop10ByResolvedAdSamAndIdNotOrderByQueuedAtDesc("jsilva", 10L))
                .thenReturn(List.of(past));

        var result = service.contactHistory(10L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(5L);
    }

    @Test
    @DisplayName("contactProfile sem sam resolvido na própria interação retorna UNAVAILABLE sem chamar o serviço de perfil")
    void contactProfile_blankSam_returnsUnavailable() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var current = CcInteraction.builder().id(10L).agent(agent).build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));

        var result = service.contactProfile(10L);

        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(contactProfileService);
    }

    @Test
    @DisplayName("contactProfile delega ao ContactProfileService usando o sam da própria interação")
    void contactProfile_delegatesWithOwnSam() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var current = CcInteraction.builder().id(10L).agent(agent).resolvedAdSam("jsilva").build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));
        when(contactProfileService.getOrTrigger("jsilva", 10L)).thenReturn(ContactProfileView.generating());

        var result = service.contactProfile(10L);

        assertThat(result.status()).isEqualTo("GENERATING");
    }

    @Test
    @DisplayName("submitContactProfileFeedback rejeita interação de outro agente (nunca confia no par interação/perfil do chamador)")
    void submitContactProfileFeedback_interactionOfAnotherAgent_rejects() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var otherAgent = CcAgent.builder().id(2L).build();
        var current = CcInteraction.builder().id(10L).agent(otherAgent).resolvedAdSam("jsilva").build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.submitContactProfileFeedback(10L, 99L, 0, true))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(contactProfileFeedbackRepository);
    }

    @Test
    @DisplayName("submitContactProfileFeedback rejeita perfil de um sam diferente do da interação")
    void submitContactProfileFeedback_profileOfDifferentSam_rejects() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var current = CcInteraction.builder().id(10L).agent(agent).resolvedAdSam("jsilva").build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));
        var profile = CcContactProfile.builder().id(99L).resolvedAdSam("outro.sam").build();
        when(contactProfileRepository.findById(99L)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.submitContactProfileFeedback(10L, 99L, 0, true))
                .isInstanceOf(ResponseStatusException.class);
        verify(contactProfileFeedbackRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitContactProfileFeedback salva quando interação e perfil pertencem ao mesmo contato")
    void submitContactProfileFeedback_savesWhenOwnedAndMatching() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var current = CcInteraction.builder().id(10L).agent(agent).resolvedAdSam("jsilva").build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));
        var profile = CcContactProfile.builder().id(99L).resolvedAdSam("jsilva").build();
        when(contactProfileRepository.findById(99L)).thenReturn(Optional.of(profile));

        service.submitContactProfileFeedback(10L, 99L, 1, false);

        verify(contactProfileFeedbackRepository).save(argThat(fb ->
                fb.getProfileId().equals(99L) && fb.getActionIndex() == 1 && !fb.getUseful() && fb.getAgentId().equals(1L)));
    }

    @Test
    @DisplayName("unifiedContactHistory sem sam resolvido na própria interação retorna lista vazia sem consultar o histórico unificado")
    void unifiedContactHistory_blankSam_returnsEmpty() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var current = CcInteraction.builder().id(10L).agent(agent).build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));

        var result = service.unifiedContactHistory(10L);

        assertThat(result).isEmpty();
        verifyNoInteractions(contactHistoryService);
    }

    @Test
    @DisplayName("unifiedContactHistory delega ao CallCenterContactHistoryService usando o sam da própria interação")
    void unifiedContactHistory_delegatesWithOwnSam() {
        var agent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(agent);
        var current = CcInteraction.builder().id(10L).agent(agent).resolvedAdSam("jsilva").build();
        when(interactionRepository.findById(10L)).thenReturn(Optional.of(current));
        var item = new ContactHistoryItem("chat", 50L, "Fila", "Agente", null, null, null);
        when(contactHistoryService.historyFor("jsilva", 10, 10L, null)).thenReturn(List.of(item));

        var result = service.unifiedContactHistory(10L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).referenceId()).isEqualTo(50L);
    }
}
