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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterAgentService — CRUD de agentes do Call Center (Fase 2). Ao criar/atualizar o ramal,
 * provisiona as tabelas ARA (ps_endpoints/ps_auths/ps_aors) além do metadado próprio
 * (cc_agents/cc_extensions) — o Asterisk passa a enxergar o ramal sem reload nem restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterAgentService {

    private final CcAgentRepository agentRepository;
    private final CcExtensionRepository extensionRepository;
    private final BusinessUnitRepository businessUnitRepository;
    private final PsEndpointRepository psEndpointRepository;
    private final PsAuthRepository psAuthRepository;
    private final PsAorRepository psAorRepository;
    private final AraQueueMemberRepository araQueueMemberRepository;
    private final CcSettingsService settingsService;

    @Transactional(readOnly = true)
    public List<CcAgent> findAll() {
        Specification<CcAgent> spec = businessUnitScope();
        return spec == null ? agentRepository.findAll() : agentRepository.findAll(spec);
    }

    @Transactional(readOnly = true)
    public CcAgent findById(Long id) {
        // Fase 19 (Parte III) — ResponseStatusException(404), não IllegalArgumentException:
        // antes caía no catch-all de RuntimeException e virava 500 genérico para id inexistente.
        var agent =
                agentRepository
                        .findById(id)
                        .orElseThrow(() -> agentNotFound(id));
        if (!inBusinessUnitScope(agent)) {
            throw agentNotFound(id);
        }
        return agent;
    }

    private ResponseStatusException agentNotFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Agente não encontrado: " + id);
    }

    /**
     * Range vigente de ramal de agente (Fase 19 — configurável em {@link CcSettingsService},
     * default 4000-4999 se nunca configurado). Reusado por {@link
     * CallCenterAgentProvisioningService} para alocar o próximo ramal livre sem duplicar a faixa.
     */
    public CcSettingsService.ExtensionRange extensionRange() {
        return settingsService.getRange(CcSettingsService.RangeType.AGENT);
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
        provisionAra(extensionNumber, secret, agent.getName());

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

    /** Ramal + secret SIP do próprio agente (Fase 13 — softphone). Já resolvido pelo chamador via
     * {@code CallCenterAgentStateService.currentAgent()} — nunca aceita um id arbitrário, então
     * não há necessidade de reaplicar o escopo por BU aqui (o agente sempre pode ver o próprio). */
    public record SipCredentials(String extension, String secret) {}

    @Transactional(readOnly = true)
    public SipCredentials sipCredentialsOf(CcAgent agent) {
        var extension =
                extensionRepository
                        .findByAgentId(agent.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Agente sem ramal provisionado: " + agent.getId()));
        return new SipCredentials(extension.getExtension(), extension.getSecret());
    }

    /** Rotaciona o secret SIP do ramal do agente — o secret circula (endpoint "me" acima), então
     * precisa ser rotacionável (Fase 13, D9-A). Atualiza {@code cc_extensions} e o auth ARA
     * (PsAuth) na mesma transação — o Asterisk lê o secret novo no próximo registro, sem reload. */
    @Transactional
    public String rotateExtensionSecret(Long agentId) {
        findById(agentId); // aplica o escopo por BU antes de rotacionar
        var extension =
                extensionRepository
                        .findByAgentId(agentId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Agente sem ramal provisionado: " + agentId));
        var newSecret = ExtensionSecretGenerator.generate();
        extension.setSecret(newSecret);
        extensionRepository.save(extension);
        psAuthRepository
                .findById(extension.getExtension() + "-auth")
                .ifPresent(
                        auth -> {
                            auth.setPassword(newSecret);
                            psAuthRepository.save(auth);
                        });
        log.info("Secret do ramal {} rotacionado (agente id={})", extension.getExtension(), agentId);
        return newSecret;
    }

    private void provisionAra(String extension, String secret, String agentName) {
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
                        // Fixo no endpoint, mesmo padrão já usado pelos ramais estáticos
                        // (1001/1002/9001/9002 em pjsip.conf.template) — sem isso, CALLERID(num)
                        // vem de qualquer From/Contact que o cliente SIP mandar no INVITE, e a
                        // Fase 23 (chamada de saída) correlaciona a interação por esse valor:
                        // um cliente SIP malicioso usando as credenciais deste ramal poderia se
                        // passar por outro agente só mudando o CallerID enviado.
                        .callerid("\"" + agentName + "\" <" + extension + ">")
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
        var range = extensionRange();
        if (num < range.start() || num > range.end()) {
            throw new IllegalArgumentException(
                    "Ramais de agente devem usar um número entre " + range.start() + " e " + range.end() + ".");
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
