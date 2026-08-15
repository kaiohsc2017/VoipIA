package com.asteriskia.domain.callcenter.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.integration.ad.AdUser;
import com.asteriskia.integration.ad.AdUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CallCenterInteractionServiceIdentityTest — cobre a resolução do bloco de identidade (Fase 14)
 * em {@code currentInteraction()}/{@code contactHistory()}, adicionada sobre o comportamento
 * pré-existente da Fase 4.
 */
class CallCenterInteractionServiceIdentityTest {

    private CcInteractionRepository interactionRepository;
    private CcDispositionRepository dispositionRepository;
    private CallCenterAgentStateService agentStateService;
    private AdUserRepository adUserRepository;
    private CallCenterInteractionService service;

    @BeforeEach
    void setUp() {
        interactionRepository = mock(CcInteractionRepository.class);
        dispositionRepository = mock(CcDispositionRepository.class);
        agentStateService = mock(CallCenterAgentStateService.class);
        adUserRepository = mock(AdUserRepository.class);
        service = new CallCenterInteractionService(
                interactionRepository, dispositionRepository, agentStateService, adUserRepository);
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
}
