package com.asteriskia.domain.callcenter.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcSettingsService;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CallCenterFlowServiceTest — Fase 5a: criação abre versão DRAFT vazia; publicar bloqueia nó não
 * implementado; publicar incrementa versão e arquiva a anterior; rollback troca o ponteiro
 * PUBLISHED↔ARCHIVED sem editar grafo; exclusão só é permitida sem versão publicada; escopo por
 * BU.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterFlowServiceTest {

    @Mock private CcFlowRepository flowRepository;
    @Mock private CcFlowVersionRepository versionRepository;
    @Mock private BusinessUnitRepository businessUnitRepository;
    @Mock private FlowGraphValidator graphValidator;
    @Mock private CcSettingsService settingsService;

    private CallCenterFlowService newService() {
        // Fase 19 (Parte III): range de ramal de fluxo deixou de ser fixo em código —
        // lenient() porque a maioria dos testes desta classe não passa entryExtension.
        lenient()
                .when(settingsService.getRange(CcSettingsService.RangeType.FLOW))
                .thenReturn(new CcSettingsService.ExtensionRange(6000, 6999));
        return new CallCenterFlowService(
                flowRepository, versionRepository, businessUnitRepository, graphValidator, settingsService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void restrictToBusinessUnits(int... buIds) {
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        for (int id : buIds) {
            authorities.add(new SimpleGrantedAuthority("BU_" + id));
        }
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("user", null, authorities));
    }

    private CcFlow flowWithId(Long id) {
        return CcFlow.builder().id(id).name("Fluxo " + id).channel("voice").build();
    }

    @Test
    @DisplayName("create rejeita nome duplicado")
    void create_duplicateName_throws() {
        var service = newService();
        when(flowRepository.findByName("URA Suporte")).thenReturn(Optional.of(flowWithId(1L)));
        var request = new FlowRequest("URA Suporte", null, "voice", null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URA Suporte");
    }

    @Test
    @DisplayName("create rejeita ramal já em uso por outro fluxo")
    void create_duplicateEntryExtension_throws() {
        var service = newService();
        when(flowRepository.findByName(any())).thenReturn(Optional.empty());
        when(flowRepository.findByEntryExtension("6001")).thenReturn(Optional.of(flowWithId(1L)));
        var request = new FlowRequest("Novo Fluxo", null, "voice", "6001", null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6001");
    }

    @Test
    @DisplayName("create rejeita businessUnitId fora do escopo do usuário restrito")
    void create_businessUnitOutOfScope_throws() {
        restrictToBusinessUnits(1);
        var service = newService();
        when(flowRepository.findByName(any())).thenReturn(Optional.empty());
        var request = new FlowRequest("Novo Fluxo", null, "voice", null, 2);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BU");
    }

    @Test
    @DisplayName("create cria fluxo e a primeira versão como DRAFT vazia")
    void create_savesFlowAndEmptyDraftVersion() {
        var service = newService();
        when(flowRepository.findByName(any())).thenReturn(Optional.empty());
        when(flowRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            CcFlow f = inv.getArgument(0);
                            f.setId(1L);
                            return f;
                        });
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new FlowRequest("Novo Fluxo", "desc", "voice", null, null);
        var flow = service.create(request);

        assertThat(flow.getId()).isEqualTo(1L);
        var versionCaptor = ArgumentCaptor.forClass(CcFlowVersion.class);
        verify(versionRepository).save(versionCaptor.capture());
        var draft = versionCaptor.getValue();
        assertThat(draft.getStatus()).isEqualTo(FlowStatus.DRAFT);
        assertThat(draft.getVersionNumber()).isEqualTo(1);
        assertThat(draft.getGraph()).contains("\"nodes\":[]");
    }

    @Test
    @DisplayName("saveDraft lança se o fluxo não tiver rascunho ativo (estado inconsistente)")
    void saveDraft_noActiveDraft_throws() {
        var service = newService();
        when(flowRepository.findById(1L)).thenReturn(Optional.of(flowWithId(1L)));
        when(versionRepository.findByFlowIdAndStatus(1L, FlowStatus.DRAFT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveDraft(1L, "{}")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("publish bloqueia quando o validador reporta erro (nó não implementado)")
    void publish_validationFails_doesNotPromoteVersion() {
        var service = newService();
        var flow = flowWithId(1L);
        var draft = CcFlowVersion.builder().id(10L).flow(flow).versionNumber(1).status(FlowStatus.DRAFT).graph("{}").build();
        when(flowRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flow));
        when(versionRepository.findByFlowIdAndStatus(1L, FlowStatus.DRAFT)).thenReturn(Optional.of(draft));
        when(graphValidator.validate("{}", "voice", true))
                .thenReturn(
                        new FlowGraphValidationResult(
                                List.of(new FlowGraphValidationResult.Issue("n2", "Nó não implementado.")), List.of()));

        var result = service.publish(1L);

        assertThat(result.isValid()).isFalse();
        assertThat(draft.getStatus()).isEqualTo(FlowStatus.DRAFT);
        assertThat(flow.getPublishedVersionId()).isNull();
        verify(versionRepository, org.mockito.Mockito.never()).save(any());
        verify(flowRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("publish promove o rascunho, cria novo rascunho e não sobrescreve versão anterior sem publicação prévia")
    void publish_validGraph_promotesDraftAndCreatesNewDraft() {
        var service = newService();
        var flow = flowWithId(1L);
        var draft = CcFlowVersion.builder().id(10L).flow(flow).versionNumber(1).status(FlowStatus.DRAFT).graph("{}").build();
        when(flowRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flow));
        when(versionRepository.findByFlowIdAndStatus(1L, FlowStatus.DRAFT)).thenReturn(Optional.of(draft));
        when(versionRepository.findByFlowIdAndStatus(1L, FlowStatus.PUBLISHED)).thenReturn(Optional.empty());
        when(versionRepository.findTopByFlowIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(draft));
        when(graphValidator.validate("{}", "voice", true))
                .thenReturn(new FlowGraphValidationResult(List.of(), List.of()));
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(flowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.publish(1L);

        assertThat(result.isValid()).isTrue();
        assertThat(draft.getStatus()).isEqualTo(FlowStatus.PUBLISHED);
        assertThat(flow.getPublishedVersionId()).isEqualTo(10L);

        var versionCaptor = ArgumentCaptor.forClass(CcFlowVersion.class);
        verify(versionRepository, org.mockito.Mockito.times(2)).save(versionCaptor.capture());
        var newDraft = versionCaptor.getAllValues().get(1);
        assertThat(newDraft.getStatus()).isEqualTo(FlowStatus.DRAFT);
        assertThat(newDraft.getVersionNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("publish arquiva a versão PUBLISHED anterior")
    void publish_archivesPreviouslyPublishedVersion() {
        var service = newService();
        var flow = flowWithId(1L);
        var previouslyPublished =
                CcFlowVersion.builder().id(5L).flow(flow).versionNumber(1).status(FlowStatus.PUBLISHED).graph("{}").build();
        var draft = CcFlowVersion.builder().id(10L).flow(flow).versionNumber(2).status(FlowStatus.DRAFT).graph("{}").build();
        when(flowRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flow));
        when(versionRepository.findByFlowIdAndStatus(1L, FlowStatus.DRAFT)).thenReturn(Optional.of(draft));
        when(versionRepository.findByFlowIdAndStatus(1L, FlowStatus.PUBLISHED))
                .thenReturn(Optional.of(previouslyPublished));
        when(versionRepository.findTopByFlowIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(draft));
        when(graphValidator.validate(any(), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new FlowGraphValidationResult(List.of(), List.of()));
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(flowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.publish(1L);

        assertThat(previouslyPublished.getStatus()).isEqualTo(FlowStatus.ARCHIVED);
        assertThat(draft.getStatus()).isEqualTo(FlowStatus.PUBLISHED);
    }

    @Test
    @DisplayName("rollback rejeita versão que não pertence ao fluxo")
    void rollback_versionFromAnotherFlow_throws() {
        var service = newService();
        var flow = flowWithId(1L);
        var otherFlow = flowWithId(2L);
        var version = CcFlowVersion.builder().id(20L).flow(otherFlow).versionNumber(1).status(FlowStatus.ARCHIVED).build();
        when(flowRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flow));
        when(versionRepository.findById(20L)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.rollback(1L, 20L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rollback rejeita versão que não está arquivada")
    void rollback_versionNotArchived_throws() {
        var service = newService();
        var flow = flowWithId(1L);
        var version = CcFlowVersion.builder().id(20L).flow(flow).versionNumber(1).status(FlowStatus.DRAFT).build();
        when(flowRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flow));
        when(versionRepository.findById(20L)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.rollback(1L, 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arquivada");
    }

    @Test
    @DisplayName("rollback promove a versão arquivada e arquiva a que estava publicada")
    void rollback_promotesArchivedVersion() {
        var service = newService();
        var flow = flowWithId(1L);
        flow.setPublishedVersionId(10L);
        var currentlyPublished =
                CcFlowVersion.builder().id(10L).flow(flow).versionNumber(2).status(FlowStatus.PUBLISHED).build();
        var target = CcFlowVersion.builder().id(5L).flow(flow).versionNumber(1).status(FlowStatus.ARCHIVED).build();
        when(flowRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flow));
        when(versionRepository.findById(5L)).thenReturn(Optional.of(target));
        when(versionRepository.findByFlowIdAndStatus(1L, FlowStatus.PUBLISHED))
                .thenReturn(Optional.of(currentlyPublished));
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(flowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.rollback(1L, 5L);

        assertThat(target.getStatus()).isEqualTo(FlowStatus.PUBLISHED);
        assertThat(currentlyPublished.getStatus()).isEqualTo(FlowStatus.ARCHIVED);
        assertThat(flow.getPublishedVersionId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("delete rejeita fluxo com versão publicada")
    void delete_flowWithPublishedVersion_throws() {
        var service = newService();
        var flow = flowWithId(1L);
        flow.setPublishedVersionId(10L);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(flow));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicada");
    }

    @Test
    @DisplayName("delete remove fluxo sem versão publicada")
    void delete_flowWithoutPublishedVersion_deletes() {
        var service = newService();
        var flow = flowWithId(1L);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(flow));

        service.delete(1L);

        verify(flowRepository).delete(flow);
    }

    @Test
    @DisplayName("findById lança quando o fluxo está fora do escopo de BU do usuário restrito")
    void findById_outOfBusinessUnitScope_throws() {
        restrictToBusinessUnits(1);
        var service = newService();
        var flow = flowWithId(1L);
        flow.setBusinessUnit(BusinessUnit.builder().id(2).build());
        when(flowRepository.findById(1L)).thenReturn(Optional.of(flow));

        // Fase 19 (Parte III): findById passou a lançar ResponseStatusException(404), não
        // IllegalArgumentException — antes caía no catch-all e virava 500 genérico.
        assertThatThrownBy(() -> service.findById(1L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    @DisplayName("findVersion lança quando o fluxo está fora do escopo de BU do usuário restrito")
    void findVersion_outOfBusinessUnitScope_throws() {
        restrictToBusinessUnits(1);
        var service = newService();
        var flow = flowWithId(1L);
        flow.setBusinessUnit(BusinessUnit.builder().id(2).build());
        var version = CcFlowVersion.builder().id(20L).flow(flow).versionNumber(1).status(FlowStatus.ARCHIVED).build();
        when(flowRepository.findById(1L)).thenReturn(Optional.of(flow));

        assertThatThrownBy(() -> service.findVersion(1L, 20L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(versionRepository, org.mockito.Mockito.never()).findById(any());
    }
}
