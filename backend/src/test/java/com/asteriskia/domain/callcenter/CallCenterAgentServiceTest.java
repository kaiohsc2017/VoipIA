package com.asteriskia.domain.callcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.ara.AraQueueMemberRepository;
import com.asteriskia.domain.callcenter.ara.PsAorRepository;
import com.asteriskia.domain.callcenter.ara.PsAuthRepository;
import com.asteriskia.domain.callcenter.ara.PsEndpoint;
import com.asteriskia.domain.callcenter.ara.PsEndpointRepository;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
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
 * CallCenterAgentServiceTest — provisionamento de ramal ARA (Fase 2 do Call Center Omnicanal):
 * faixa de numeração reservada (4000-4999), ramal duplicado bloqueado, criação escreve tanto o
 * metadado (cc_agents/cc_extensions) quanto as tabelas ARA (ps_endpoints/ps_auths/ps_aors).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterAgentServiceTest {

    @Mock private CcAgentRepository agentRepository;
    @Mock private CcExtensionRepository extensionRepository;
    @Mock private BusinessUnitRepository businessUnitRepository;
    @Mock private PsEndpointRepository psEndpointRepository;
    @Mock private PsAuthRepository psAuthRepository;
    @Mock private PsAorRepository psAorRepository;
    @Mock private AraQueueMemberRepository araQueueMemberRepository;
    @Mock private CcSettingsService settingsService;

    private CallCenterAgentService newService() {
        // Fase 19 (Parte III): range deixou de ser constante estática — lenient() porque nem
        // todo teste chega a validar o range (ex.: falha antecipada por ramal duplicado).
        lenient()
                .when(settingsService.getRange(CcSettingsService.RangeType.AGENT))
                .thenReturn(new CcSettingsService.ExtensionRange(4000, 4999));
        return new CallCenterAgentService(
                agentRepository,
                extensionRepository,
                businessUnitRepository,
                psEndpointRepository,
                psAuthRepository,
                psAorRepository,
                araQueueMemberRepository,
                settingsService);
    }

    @Test
    @DisplayName("create rejeita ramal fora da faixa 4000-4999")
    void create_extensionOutOfRange_throws() {
        var service = newService();
        var request = new AgentRequest("Agente Teste", null, null, "1999");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4000")
                .hasMessageContaining("4999");
    }

    @Test
    @DisplayName("create rejeita ramal já em uso por outro agente")
    void create_duplicateExtension_throws() {
        var service = newService();
        var request = new AgentRequest("Agente Teste", null, null, "4001");
        when(extensionRepository.findByExtension("4001"))
                .thenReturn(Optional.of(CcExtension.builder().extension("4001").build()));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4001");
    }

    @Test
    @DisplayName("create provisiona endpoint/auth/aor em ARA além do metadado próprio")
    void create_validRequest_provisionsAraAndMetadata() {
        var service = newService();
        var request = new AgentRequest("Agente Teste", null, null, "4001");
        when(extensionRepository.findByExtension("4001")).thenReturn(Optional.empty());
        when(agentRepository.save(any(CcAgent.class)))
                .thenAnswer(inv -> {
                    CcAgent a = inv.getArgument(0);
                    a.setId(1L);
                    return a;
                });
        when(extensionRepository.save(any(CcExtension.class))).thenAnswer(inv -> inv.getArgument(0));

        var agent = service.create(request);

        assertThat(agent.getId()).isEqualTo(1L);
        assertThat(agent.getExtension().getExtension()).isEqualTo("4001");

        var endpointCaptor = ArgumentCaptor.forClass(PsEndpoint.class);
        verify(psEndpointRepository).save(endpointCaptor.capture());
        var endpoint = endpointCaptor.getValue();
        assertThat(endpoint.getId()).isEqualTo("4001");
        assertThat(endpoint.getAors()).isEqualTo("4001");
        assertThat(endpoint.getAuth()).isEqualTo("4001-auth");
        assertThat(endpoint.getContext()).isEqualTo("ramais-internos");
        // Fase 23 — callerid fixo no endpoint (mesmo padrão dos ramais estáticos em
        // pjsip.conf.template): sem isso, CALLERID(num) viria do From/Contact que o cliente SIP
        // mandasse no INVITE, permitindo se passar por outro agente numa chamada de saída.
        assertThat(endpoint.getCallerid()).contains("Agente Teste").contains("4001");

        verify(psAuthRepository).save(any());
        verify(psAorRepository).save(any());
    }

    @Test
    @DisplayName("delete desprovisiona ps_endpoints/ps_auths/ps_aors quando o agente tem ramal")
    void delete_agentWithExtension_deprovisionsAra() {
        var service = newService();
        var agent = CcAgent.builder().id(1L).name("Agente Teste").build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));
        when(extensionRepository.findByAgentId(1L))
                .thenReturn(Optional.of(CcExtension.builder().extension("4001").build()));

        service.delete(1L);

        verify(psEndpointRepository).deleteById("4001");
        verify(psAorRepository).deleteById("4001");
        verify(psAuthRepository).deleteById("4001-auth");
        verify(araQueueMemberRepository).deleteByInterfaceName("PJSIP/4001");
        verify(agentRepository).delete(agent);
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
    @DisplayName("create rejeita businessUnitId fora do escopo do usuário restrito")
    void create_businessUnitOutOfScope_throws() {
        restrictToBusinessUnits(1);
        var service = newService();
        var request = new AgentRequest("Agente Teste", null, 2, "4002");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BU");
    }

    @Test
    @DisplayName("update rejeita alteração do ramal do agente")
    void update_extensionChanged_throws() {
        var service = newService();
        var extension = CcExtension.builder().extension("4001").build();
        var agent = CcAgent.builder().id(1L).name("Agente Teste").extension(extension).build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));

        var request = new AgentRequest("Agente Teste", null, null, "4002");

        assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update aceita o mesmo ramal do agente")
    void update_sameExtension_succeeds() {
        var service = newService();
        var extension = CcExtension.builder().extension("4001").build();
        var agent = CcAgent.builder().id(1L).name("Agente Teste").extension(extension).build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));
        when(agentRepository.save(any(CcAgent.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new AgentRequest("Agente Renomeado", null, null, "4001");
        var updated = service.update(1L, request);

        assertThat(updated.getName()).isEqualTo("Agente Renomeado");
    }

    @Test
    @DisplayName("sipCredentialsOf devolve ramal e secret do agente já resolvido (Fase 13)")
    void sipCredentialsOf_returnsExtensionAndSecret() {
        var service = newService();
        var agent = CcAgent.builder().id(5L).build();
        var extension = CcExtension.builder().extension("4005").secret("segredo123").build();
        when(extensionRepository.findByAgentId(5L)).thenReturn(Optional.of(extension));

        var credentials = service.sipCredentialsOf(agent);

        assertThat(credentials.extension()).isEqualTo("4005");
        assertThat(credentials.secret()).isEqualTo("segredo123");
    }

    @Test
    @DisplayName("sipCredentialsOf falha com erro claro se o agente não tem ramal provisionado")
    void sipCredentialsOf_noExtension_throws() {
        var service = newService();
        var agent = CcAgent.builder().id(6L).build();
        when(extensionRepository.findByAgentId(6L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sipCredentialsOf(agent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sem ramal provisionado");
    }

    @Test
    @DisplayName("rotateExtensionSecret gera um novo secret e espelha no auth ARA (PsAuth)")
    void rotateExtensionSecret_generatesNewSecretAndMirrorsAra() {
        var agent = CcAgent.builder().id(7L).build();
        when(agentRepository.findById(7L)).thenReturn(Optional.of(agent));
        var extension = CcExtension.builder().extension("4007").secret("antigo").build();
        when(extensionRepository.findByAgentId(7L)).thenReturn(Optional.of(extension));
        var auth = com.asteriskia.domain.callcenter.ara.PsAuth.builder().id("4007-auth").password("antigo").build();
        when(psAuthRepository.findById("4007-auth")).thenReturn(Optional.of(auth));

        var newSecret = newService().rotateExtensionSecret(7L);

        assertThat(newSecret).isNotEqualTo("antigo");
        assertThat(extension.getSecret()).isEqualTo(newSecret);
        assertThat(auth.getPassword()).isEqualTo(newSecret);
        verify(extensionRepository).save(extension);
        verify(psAuthRepository).save(auth);
    }

    @Test
    @DisplayName("rotateExtensionSecret falha com erro claro se o agente não tem ramal provisionado")
    void rotateExtensionSecret_noExtension_throws() {
        var agent = CcAgent.builder().id(8L).build();
        when(agentRepository.findById(8L)).thenReturn(Optional.of(agent));
        when(extensionRepository.findByAgentId(8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().rotateExtensionSecret(8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sem ramal provisionado");
    }
}
