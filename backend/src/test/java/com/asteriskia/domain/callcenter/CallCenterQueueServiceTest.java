package com.asteriskia.domain.callcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.ara.AraQueueMemberRepository;
import com.asteriskia.domain.callcenter.ara.AraQueueRepository;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CallCenterQueueServiceTest — faixa de numeração reservada (5000-5999), duplicidade e o
 * espelhamento em queue_members (ARA) ao incluir um agente na fila.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterQueueServiceTest {

    @Mock private CcQueueRepository queueRepository;
    @Mock private CcQueueMemberRepository memberRepository;
    @Mock private CcAgentRepository agentRepository;
    @Mock private CcExtensionRepository extensionRepository;
    @Mock private BusinessUnitRepository businessUnitRepository;
    @Mock private AraQueueRepository araQueueRepository;
    @Mock private AraQueueMemberRepository araQueueMemberRepository;
    @Mock private CcSettingsService settingsService;

    private CallCenterQueueService newService() {
        // Fase 19 (Parte III): range deixou de ser constante estática — lenient() porque nem
        // todo teste chega a validar o range (ex.: falha antecipada por nome duplicado).
        lenient()
                .when(settingsService.getRange(CcSettingsService.RangeType.QUEUE))
                .thenReturn(new CcSettingsService.ExtensionRange(5000, 5999));
        var service =
                new CallCenterQueueService(
                        queueRepository,
                        memberRepository,
                        agentRepository,
                        extensionRepository,
                        businessUnitRepository,
                        araQueueRepository,
                        araQueueMemberRepository,
                        settingsService);
        setRecordingBasePath(service, "/opt/telecom/gravacao");
        return service;
    }

    private static void setRecordingBasePath(CallCenterQueueService service, String path) {
        try {
            Field field = CallCenterQueueService.class.getDeclaredField("recordingBasePath");
            field.setAccessible(true);
            field.set(service, path);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("create rejeita fila fora da faixa 5000-5999")
    void create_outOfRange_throws() {
        var service = newService();
        var request = new QueueRequest("4999", "Fila Teste", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5000");
    }

    @Test
    @DisplayName("create rejeita número de fila já em uso")
    void create_duplicateName_throws() {
        var service = newService();
        var request = new QueueRequest("5001", "Fila Teste", null, null, null, null, null, null);
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(CcQueue.builder().name("5001").build()));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5001");
    }

    @Test
    @DisplayName("addMember espelha o agente em queue_members (ARA) com a interface PJSIP correta")
    void addMember_validAgent_mirrorsAraQueueMember() {
        var service = newService();
        var queue = CcQueue.builder().id(1L).name("5001").build();
        var agent = CcAgent.builder().id(2L).name("Agente Teste").build();
        var extension = CcExtension.builder().extension("4001").build();

        when(queueRepository.findById(1L)).thenReturn(Optional.of(queue));
        when(agentRepository.findById(2L)).thenReturn(Optional.of(agent));
        when(memberRepository.findByQueueIdAndAgentId(1L, 2L)).thenReturn(Optional.empty());
        when(extensionRepository.findByAgentId(2L)).thenReturn(Optional.of(extension));
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addMember(1L, 2L);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.asteriskia.domain.callcenter.ara.AraQueueMember.class);
        verify(araQueueMemberRepository).save(captor.capture());
        assertThat(captor.getValue().getQueueName()).isEqualTo("5001");
        assertThat(captor.getValue().getInterfaceName()).isEqualTo("PJSIP/4001");
    }

    @Test
    @DisplayName("addMember rejeita agente sem ramal provisionado")
    void addMember_agentWithoutExtension_throws() {
        var service = newService();
        var queue = CcQueue.builder().id(1L).name("5001").build();
        var agent = CcAgent.builder().id(2L).name("Agente Teste").build();

        when(queueRepository.findById(1L)).thenReturn(Optional.of(queue));
        when(agentRepository.findById(2L)).thenReturn(Optional.of(agent));
        when(memberRepository.findByQueueIdAndAgentId(1L, 2L)).thenReturn(Optional.empty());
        when(extensionRepository.findByAgentId(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(1L, 2L)).isInstanceOf(IllegalArgumentException.class);
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

    @Test
    @DisplayName("create rejeita estratégia fora da allowlist")
    void create_invalidStrategy_throws() {
        var service = newService();
        var request = new QueueRequest("5002", "Fila Teste", null, "estrategia-inventada", null, null, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strat");
    }

    @Test
    @DisplayName("create rejeita timeout fora dos limites")
    void create_timeoutOutOfBounds_throws() {
        var service = newService();
        var request = new QueueRequest("5003", "Fila Teste", null, null, -1, null, null, null);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create rejeita businessUnitId fora do escopo do usuário restrito")
    void create_businessUnitOutOfScope_throws() {
        restrictToBusinessUnits(1);
        var service = newService();
        var request = new QueueRequest("5004", "Fila Teste", 2, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BU");
    }

    @Test
    @DisplayName("create rejeita consentMessagePath fora do diretório de avisos")
    void create_consentPathOutsideBaseDir_throws() {
        var service = newService();
        var request =
                new QueueRequest(
                        "5005", "Fila Teste", null, null, null, null, "/etc/passwd", null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aviso de gravação");
    }

    @Test
    @DisplayName("create rejeita consentMessagePath com path traversal para fora do diretório de avisos")
    void create_consentPathTraversal_throws() {
        var service = newService();
        var request =
                new QueueRequest(
                        "5006",
                        "Fila Teste",
                        null,
                        null,
                        null,
                        null,
                        "/opt/telecom/gravacao/avisos/../../../etc/passwd",
                        null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aviso de gravação");
    }

    @Test
    @DisplayName("create aceita consentMessagePath dentro do diretório de avisos")
    void create_consentPathWithinBaseDir_persists() {
        var service = newService();
        var request =
                new QueueRequest(
                        "5007",
                        "Fila Teste",
                        null,
                        null,
                        null,
                        null,
                        "/opt/telecom/gravacao/avisos/consentimento.wav",
                        null);
        when(queueRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var saved = service.create(request);

        assertThat(saved.getConsentMessagePath()).isEqualTo("/opt/telecom/gravacao/avisos/consentimento.wav");
    }

    @Test
    @DisplayName("members rejeita fila fora do escopo por BU do usuário restrito")
    void members_queueOutOfScope_throws() {
        restrictToBusinessUnits(1);
        var otherBu = BusinessUnit.builder().id(2).build();
        var queue = CcQueue.builder().id(1L).name("5001").businessUnit(otherBu).build();
        when(queueRepository.findById(1L)).thenReturn(Optional.of(queue));
        var service = newService();

        // Fase 19 (Parte III): findById passou a lançar ResponseStatusException(404), não
        // IllegalArgumentException — antes caía no catch-all e virava 500 genérico.
        assertThatThrownBy(() -> service.members(1L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    @DisplayName("members retorna a lista quando a fila está no escopo do usuário")
    void members_queueInScope_returnsList() {
        restrictToBusinessUnits(1);
        var ownBu = BusinessUnit.builder().id(1).build();
        var queue = CcQueue.builder().id(1L).name("5001").businessUnit(ownBu).build();
        when(queueRepository.findById(1L)).thenReturn(Optional.of(queue));
        when(memberRepository.findByQueueId(1L)).thenReturn(List.of());
        var service = newService();

        assertThat(service.members(1L)).isEmpty();
    }

    @Test
    @DisplayName("addMember com prioridade persiste o penalty e espelha em ARA (Fase 12.3)")
    void addMember_withPenalty_persistsAndMirrors() {
        var service = newService();
        var queue = CcQueue.builder().id(1L).name("5001").build();
        var agent = CcAgent.builder().id(2L).name("Agente Teste").build();
        var extension = CcExtension.builder().extension("4001").build();

        when(queueRepository.findById(1L)).thenReturn(Optional.of(queue));
        when(agentRepository.findById(2L)).thenReturn(Optional.of(agent));
        when(memberRepository.findByQueueIdAndAgentId(1L, 2L)).thenReturn(Optional.empty());
        when(extensionRepository.findByAgentId(2L)).thenReturn(Optional.of(extension));
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var member = service.addMember(1L, 2L, 5);

        assertThat(member.getPenalty()).isEqualTo(5);
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.asteriskia.domain.callcenter.ara.AraQueueMember.class);
        verify(araQueueMemberRepository).save(captor.capture());
        assertThat(captor.getValue().getPenalty()).isEqualTo(5);
    }

    @Test
    @DisplayName("addMember rejeita prioridade negativa")
    void addMember_negativePenalty_throws() {
        var service = newService();
        assertThatThrownBy(() -> service.addMember(1L, 2L, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativa");
    }

    @Test
    @DisplayName("updateMemberPenalty atualiza o penalty do membro e espelha em ARA")
    void updateMemberPenalty_updatesAndMirrors() {
        var service = newService();
        var queue = CcQueue.builder().id(1L).name("5001").build();
        var agent = CcAgent.builder().id(2L).build();
        var extension = CcExtension.builder().extension("4001").build();
        var member = CcQueueMember.builder().id(7L).queue(queue).agent(agent).penalty(0).build();
        var araMember =
                com.asteriskia.domain.callcenter.ara.AraQueueMember.builder()
                        .queueName("5001").interfaceName("PJSIP/4001").penalty(0).build();

        when(queueRepository.findById(1L)).thenReturn(Optional.of(queue));
        when(memberRepository.findByQueueIdAndAgentId(1L, 2L)).thenReturn(Optional.of(member));
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(extensionRepository.findByAgentId(2L)).thenReturn(Optional.of(extension));
        when(araQueueMemberRepository.findByQueueNameAndInterfaceName("5001", "PJSIP/4001"))
                .thenReturn(Optional.of(araMember));

        var updated = service.updateMemberPenalty(1L, 2L, 3);

        assertThat(updated.getPenalty()).isEqualTo(3);
        assertThat(araMember.getPenalty()).isEqualTo(3);
        verify(araQueueMemberRepository).save(araMember);
    }

    @Test
    @DisplayName("create com copyMembersFromQueueId clona os membros da fila de origem")
    void create_withCopyMembersFromQueueId_clonesMembers() {
        var service = newService();
        var sourceQueue = CcQueue.builder().id(1L).name("5001").build();
        var agent = CcAgent.builder().id(2L).name("Agente Teste").build();
        var sourceMember = CcQueueMember.builder().id(9L).queue(sourceQueue).agent(agent).penalty(4).build();
        var extension = CcExtension.builder().extension("4001").build();

        when(queueRepository.findByName("5010")).thenReturn(Optional.empty());
        when(queueRepository.save(any())).thenAnswer(inv -> {
            CcQueue q = inv.getArgument(0);
            q.setId(20L);
            return q;
        });
        when(queueRepository.findById(1L)).thenReturn(Optional.of(sourceQueue));
        when(memberRepository.findByQueueId(1L)).thenReturn(List.of(sourceMember));
        when(queueRepository.findById(20L)).thenReturn(Optional.of(
                CcQueue.builder().id(20L).name("5010").build()));
        when(agentRepository.findById(2L)).thenReturn(Optional.of(agent));
        when(memberRepository.findByQueueIdAndAgentId(20L, 2L)).thenReturn(Optional.empty());
        when(extensionRepository.findByAgentId(2L)).thenReturn(Optional.of(extension));
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new QueueRequest("5010", "Fila Clonada", null, null, null, null, null, 1L);
        service.create(request);

        verify(memberRepository).findByQueueId(1L);
        var captor = org.mockito.ArgumentCaptor.forClass(CcQueueMember.class);
        verify(memberRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(m -> assertThat(m.getPenalty()).isEqualTo(4));
    }

    @Test
    @DisplayName("create com copyMembersFromQueueId de fila fora do escopo de BU falha limpo")
    void create_copyFromQueueOutOfScope_throws() {
        restrictToBusinessUnits(1);
        var service = newService();
        var otherBu = BusinessUnit.builder().id(2).build();
        var sourceQueue = CcQueue.builder().id(1L).name("5001").businessUnit(otherBu).build();

        when(queueRepository.findByName("5011")).thenReturn(Optional.empty());
        when(queueRepository.save(any())).thenAnswer(inv -> {
            CcQueue q = inv.getArgument(0);
            q.setId(21L);
            return q;
        });
        when(queueRepository.findById(1L)).thenReturn(Optional.of(sourceQueue));

        var request = new QueueRequest("5011", "Fila Nova", null, null, null, null, null, 1L);
        // Fase 19 (Parte III): findById (usado por copyMembers) passou a lançar
        // ResponseStatusException(404), não IllegalArgumentException.
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
