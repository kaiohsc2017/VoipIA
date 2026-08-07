package com.asteriskia.domain.callcenter;

import com.asteriskia.domain.callcenter.ara.AraQueueMemberRepository;
import com.asteriskia.domain.callcenter.ara.PsAor;
import com.asteriskia.domain.callcenter.ara.PsAorRepository;
import com.asteriskia.domain.callcenter.ara.PsAuth;
import com.asteriskia.domain.callcenter.ara.PsAuthRepository;
import com.asteriskia.domain.callcenter.ara.PsEndpoint;
import com.asteriskia.domain.callcenter.ara.PsEndpointRepository;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterAgentService — CRUD de agentes do Call Center (Fase 2). Ao criar/atualizar o ramal,
 * provisiona as tabelas ARA (ps_endpoints/ps_auths/ps_aors) além do metadado próprio
 * (cc_agents/cc_extensions) — o Asterisk passa a enxergar o ramal sem reload nem restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterAgentService {

    private static final int RANGE_START = 4000;
    private static final int RANGE_END = 4999;

    private final CcAgentRepository agentRepository;
    private final CcExtensionRepository extensionRepository;
    private final BusinessUnitRepository businessUnitRepository;
    private final PsEndpointRepository psEndpointRepository;
    private final PsAuthRepository psAuthRepository;
    private final PsAorRepository psAorRepository;
    private final AraQueueMemberRepository araQueueMemberRepository;

    @Transactional(readOnly = true)
    public List<CcAgent> findAll() {
        Specification<CcAgent> spec = businessUnitScope();
        return spec == null ? agentRepository.findAll() : agentRepository.findAll(spec);
    }

    @Transactional(readOnly = true)
    public CcAgent findById(Long id) {
        var agent =
                agentRepository
                        .findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Agente não encontrado: " + id));
        if (!inBusinessUnitScope(agent)) {
            throw new IllegalArgumentException("Agente não encontrado: " + id);
        }
        return agent;
    }

    /**
     * Achado de segurança (code-reviewer): findById era o único ponto de leitura usado por
     * update/delete/extensionSecret, mas não aplicava escopo por BU (diferente de findAll) —
     * permitia a um usuário restrito a uma BU ler/editar/remover agente (e revelar a senha do
     * ramal) de outra BU. Mesmo padrão de CallRecordService.inBusinessUnitScope.
     */
    private boolean inBusinessUnitScope(CcAgent agent) {
        if (!BusinessUnitContext.isRestricted()) {
            return true;
        }
        return agent.getBusinessUnit() == null
                || BusinessUnitContext.currentBusinessUnitIds().contains(agent.getBusinessUnit().getId());
    }

    @Transactional
    public CcAgent create(AgentRequest request) {
        var extensionNumber = request.extension().trim();
        validateExtensionRange(extensionNumber);
        extensionRepository
                .findByExtension(extensionNumber)
                .ifPresent(
                        e -> {
                            throw new IllegalArgumentException(
                                    "Já existe um ramal usando " + extensionNumber + ".");
                        });

        var agent =
                CcAgent.builder()
                        .name(request.name())
                        .userId(request.userId())
                        .businessUnit(resolveBusinessUnit(request.businessUnitId()))
                        .active(true)
                        .build();
        agent = agentRepository.save(agent);

        var secret = ExtensionSecretGenerator.generate();
        provisionAra(extensionNumber, secret);

        var extension =
                CcExtension.builder()
                        .agent(agent)
                        .extension(extensionNumber)
                        .secret(secret)
                        .build();
        extensionRepository.save(extension);
        agent.setExtension(extension);

        log.info("Agente do Call Center criado: id={} ramal={}", agent.getId(), extensionNumber);
        return agent;
    }

    @Transactional
    public CcAgent update(Long id, AgentRequest request) {
        var agent = findById(id);
        // Achado de code-review: o ramal é imutável por aqui (reprovisionar exige passar por
        // delete+create, que desprovisiona/reprovisiona ARA corretamente) — antes esse campo era
        // aceito e silenciosamente ignorado; agora rejeita explicitamente qualquer divergência em
        // vez de mascarar a intenção do chamador. O frontend já desabilita esse campo na edição.
        var currentExtension = agent.getExtension() == null ? null : agent.getExtension().getExtension();
        if (currentExtension != null && !currentExtension.equals(request.extension().trim())) {
            throw new IllegalArgumentException(
                    "Ramal não pode ser alterado por aqui — remova e recrie o agente.");
        }
        agent.setName(request.name());
        agent.setUserId(request.userId());
        agent.setBusinessUnit(resolveBusinessUnit(request.businessUnitId()));
        return agentRepository.save(agent);
    }

    @Transactional
    public void delete(Long id) {
        var agent = findById(id);
        extensionRepository
                .findByAgentId(id)
                .ifPresent(
                        ext -> {
                            // Achado de code-review: sem isso, um agente removido enquanto ainda
                            // membro de uma fila deixava uma linha órfã em queue_members (ARA) —
                            // app_queue continuaria enxergando um membro PJSIP/<ramal> inexistente.
                            // cc_queue_members (metadado nosso) já cai sozinho via ON DELETE CASCADE.
                            araQueueMemberRepository.deleteByInterfaceName("PJSIP/" + ext.getExtension());
                            deprovisionAra(ext.getExtension());
                            extensionRepository.delete(ext);
                        });
        agentRepository.delete(agent);
    }

    /** Senha do ramal — exposta só sob demanda (rota protegida por callcenter.ramais). */
    @Transactional(readOnly = true)
    public String extensionSecret(Long agentId) {
        findById(agentId); // aplica o escopo por BU antes de revelar a senha do ramal
        return extensionRepository
                .findByAgentId(agentId)
                .map(CcExtension::getSecret)
                .orElseThrow(
                        () -> new IllegalArgumentException("Agente sem ramal provisionado: " + agentId));
    }

    private void provisionAra(String extension, String secret) {
        psAuthRepository.save(
                PsAuth.builder()
                        .id(extension + "-auth")
                        .authType("userpass")
                        .password(secret)
                        .username(extension)
                        .build());
        psAorRepository.save(
                PsAor.builder().id(extension).maxContacts(1).removeExisting("yes").build());
        psEndpointRepository.save(
                PsEndpoint.builder()
                        .id(extension)
                        .transport("transport-udp")
                        .aors(extension)
                        .auth(extension + "-auth")
                        .context("ramais-internos")
                        .disallow("all")
                        .allow("alaw,ulaw")
                        .directMedia("no")
                        .forceRport("yes")
                        .rewriteContact("yes")
                        .identifyBy("username,auth_username")
                        .build());
    }

    private void deprovisionAra(String extension) {
        psEndpointRepository.deleteById(extension);
        psAorRepository.deleteById(extension);
        psAuthRepository.deleteById(extension + "-auth");
    }

    private void validateExtensionRange(String extension) {
        int num;
        try {
            num = Integer.parseInt(extension.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ramal inválido: " + extension);
        }
        if (num < RANGE_START || num > RANGE_END) {
            throw new IllegalArgumentException(
                    "Ramais de agente devem usar um número entre " + RANGE_START + " e " + RANGE_END + ".");
        }
    }

    /**
     * Achado de segurança (security-reviewer): validava só que a BU existia, não que o usuário
     * restrito tinha acesso a ela — permitia mover um agente para uma BU que o usuário não
     * gerencia. Mesmo tipo de checagem já aplicada em findById.
     */
    private com.asteriskia.domain.masterdata.BusinessUnit resolveBusinessUnit(Integer businessUnitId) {
        if (businessUnitId == null) {
            return null;
        }
        if (BusinessUnitContext.isRestricted()
                && !BusinessUnitContext.currentBusinessUnitIds().contains(businessUnitId)) {
            throw new IllegalArgumentException("BU não encontrada: " + businessUnitId);
        }
        return businessUnitRepository
                .findById(businessUnitId)
                .orElseThrow(
                        () -> new IllegalArgumentException("BU não encontrada: " + businessUnitId));
    }

    private Specification<CcAgent> businessUnitScope() {
        if (!BusinessUnitContext.isRestricted()) {
            return null;
        }
        return CallCenterSpecifications.agentRestrictedToBusinessUnits(
                BusinessUnitContext.currentBusinessUnitIds());
    }
}
