package com.asteriskia.domain.callcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private CallCenterQueueService newService() {
        var service =
                new CallCenterQueueService(
                        queueRepository,
                        memberRepository,
                        agentRepository,
                        extensionRepository,
                        businessUnitRepository,
                        araQueueRepository,
                        araQueueMemberRepository);
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
        var request = new QueueRequest("4999", "Fila Teste", null, null, null, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5000");
    }

    @Test
    @DisplayName("create rejeita número de fila já em uso")
    void create_duplicateName_throws() {
        var service = newService();
        var request = new QueueRequest("5001", "Fila Teste", null, null, null, null, null);
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
        var request = new QueueRequest("5002", "Fila Teste", null, "estrategia-inventada", null, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strat");
    }

    @Test
    @DisplayName("create rejeita timeout fora dos limites")
    void create_timeoutOutOfBounds_throws() {
        var service = newService();
        var request = new QueueRequest("5003", "Fila Teste", null, null, -1, null, null);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create rejeita businessUnitId fora do escopo do usuário restrito")
    void create_businessUnitOutOfScope_throws() {
        restrictToBusinessUnits(1);
        var service = newService();
        var request = new QueueRequest("5004", "Fila Teste", 2, null, null, null, null);

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
                        "5005", "Fila Teste", null, null, null, null, "/etc/passwd");

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
                        "/opt/telecom/gravacao/avisos/../../../etc/passwd");

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
                        "/opt/telecom/gravacao/avisos/consentimento.wav");
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

        assertThatThrownBy(() -> service.members(1L)).isInstanceOf(IllegalArgumentException.class);
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
}
