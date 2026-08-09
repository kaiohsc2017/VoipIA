package com.asteriskia.domain.callcenter;

import com.asteriskia.domain.callcenter.ara.AraQueue;
import com.asteriskia.domain.callcenter.ara.AraQueueMember;
import com.asteriskia.domain.callcenter.ara.AraQueueMemberRepository;
import com.asteriskia.domain.callcenter.ara.AraQueueRepository;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterQueueService — CRUD de filas do Call Center (Fase 2). Espelha cada fila e cada
 * membro em `queues`/`queue_members` (ARA, V46) — o app_queue passa a enxergar sem reload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterQueueService {

    private static final int RANGE_START = 5000;
    private static final int RANGE_END = 5999;
    private static final int MAX_TIMEOUT_SECONDS = 3600;
    // Subdiretório fixo (dentro do mesmo caminho configurável de gravação) para os áudios de
    // aviso de gravação (Fase 3) — evita que consentMessagePath aponte para um arquivo arbitrário
    // do container Asterisk. Lê a mesma property de CallCenterRecordingService para não divergir
    // se CALLCENTER_RECORDING_PATH for alterado.
    @Value("${app.callcenter.recording-path:/opt/gravacoes/audio}")
    private String recordingBasePath;
    // Estratégias nativas do app_queue do Asterisk — mesma lista oferecida no <select> do
    // frontend (FilasTab.tsx). Allowlist evita gravar um valor arbitrário em queues.strategy
    // (ARA) via chamada direta à API, sem passar pela SPA.
    private static final java.util.Set<String> VALID_STRATEGIES =
            java.util.Set.of("ringall", "leastrecent", "fewestcalls", "random", "rrmemory", "linear", "wrandom");

    private final CcQueueRepository queueRepository;
    private final CcQueueMemberRepository memberRepository;
    private final CcAgentRepository agentRepository;
    private final CcExtensionRepository extensionRepository;
    private final BusinessUnitRepository businessUnitRepository;
    private final AraQueueRepository araQueueRepository;
    private final AraQueueMemberRepository araQueueMemberRepository;

    @Transactional(readOnly = true)
    public List<CcQueue> findAll() {
        Specification<CcQueue> spec = businessUnitScope();
        return spec == null ? queueRepository.findAll() : queueRepository.findAll(spec);
    }

    @Transactional(readOnly = true)
    public CcQueue findById(Long id) {
        var queue =
                queueRepository
                        .findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Fila não encontrada: " + id));
        if (!inBusinessUnitScope(queue)) {
            throw new IllegalArgumentException("Fila não encontrada: " + id);
        }
        return queue;
    }

    /** Mesmo achado de segurança do CallCenterAgentService — findById é reusado por
     * update/delete/addMember/removeMember e precisa aplicar o mesmo escopo de findAll. */
    private boolean inBusinessUnitScope(CcQueue queue) {
        if (!BusinessUnitContext.isRestricted()) {
            return true;
        }
        return queue.getBusinessUnit() == null
                || BusinessUnitContext.currentBusinessUnitIds().contains(queue.getBusinessUnit().getId());
    }

    /**
     * addMember/removeMember buscavam o agente direto no repositório, sem escopo por BU (mesmo
     * achado do code-reviewer aplicado aqui) — permitiria incluir/remover um agente de outra BU
     * numa fila. Duplica a checagem de CallCenterAgentService de propósito (mesmo padrão de
     * Ura/CallRecordService, cada service com seu próprio guard) em vez de acoplar os dois
     * services só por essa validação.
     */
    private CcAgent findAgentInScope(Long agentId) {
        var agent =
                agentRepository
                        .findById(agentId)
                        .orElseThrow(() -> new IllegalArgumentException("Agente não encontrado: " + agentId));
        if (BusinessUnitContext.isRestricted()
                && agent.getBusinessUnit() != null
                && !BusinessUnitContext.currentBusinessUnitIds().contains(agent.getBusinessUnit().getId())) {
            throw new IllegalArgumentException("Agente não encontrado: " + agentId);
        }
        return agent;
    }

    @Transactional
    public CcQueue create(QueueRequest request) {
        var name = request.name().trim();
        validateExtensionRange(name);
        queueRepository
                .findByName(name)
                .ifPresent(
                        q -> {
                            throw new IllegalArgumentException("Já existe uma fila usando " + name + ".");
                        });

        var strategy = resolveStrategy(request.strategy());
        var timeout = resolveTimeout(request.timeoutSeconds());

        var queue =
                CcQueue.builder()
                        .name(name)
                        .displayName(request.displayName())
                        .businessUnit(resolveBusinessUnit(request.businessUnitId()))
                        .strategy(strategy)
                        .timeoutSeconds(timeout)
                        .active(true)
                        .recordingEnabled(request.recordingEnabled() == null || request.recordingEnabled())
                        .consentMessagePath(normalizeConsentPath(request.consentMessagePath()))
                        .build();
        queue = queueRepository.save(queue);

        araQueueRepository.save(
                AraQueue.builder()
                        .name(name)
                        .context("ramais-internos")
                        .strategy(strategy)
                        .timeout(timeout)
                        .maxlen(0)
                        .musiconhold("default")
                        .wrapuptime(0)
                        .build());

        log.info("Fila do Call Center criada: id={} numero={}", queue.getId(), name);

        if (request.copyMembersFromQueueId() != null) {
            copyMembers(request.copyMembersFromQueueId(), queue);
        }
        return queue;
    }

    /**
     * Fase 12.5 — clona os membros da fila de origem (agente + prioridade) para a fila recém-
     * criada. {@code findById} aplica o escopo por BU na origem — copiar de uma fila de outra BU
     * seria vazamento de composição de equipe (quem são os agentes daquela fila) para quem não
     * deveria nem saber que ela existe.
     */
    private void copyMembers(Long sourceQueueId, CcQueue targetQueue) {
        var sourceQueue = findById(sourceQueueId);
        memberRepository
                .findByQueueId(sourceQueue.getId())
                .forEach(sourceMember -> addMember(targetQueue.getId(), sourceMember.getAgent().getId(), sourceMember.getPenalty()));
    }

    @Transactional
    public CcQueue update(Long id, QueueRequest request) {
        var queue = findById(id);
        queue.setDisplayName(request.displayName());
        queue.setBusinessUnit(resolveBusinessUnit(request.businessUnitId()));
        if (request.strategy() != null && !request.strategy().isBlank()) {
            queue.setStrategy(resolveStrategy(request.strategy()));
        }
        if (request.timeoutSeconds() != null) {
            queue.setTimeoutSeconds(resolveTimeout(request.timeoutSeconds()));
        }
        if (request.recordingEnabled() != null) {
            queue.setRecordingEnabled(request.recordingEnabled());
        }
        queue.setConsentMessagePath(normalizeConsentPath(request.consentMessagePath()));
        var saved = queueRepository.save(queue);

        araQueueRepository
                .findById(saved.getName())
                .ifPresent(
                        araQueue -> {
                            araQueue.setStrategy(saved.getStrategy());
                            araQueue.setTimeout(saved.getTimeoutSeconds());
                            araQueueRepository.save(araQueue);
                        });
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        var queue = findById(id);
        araQueueMemberRepository.findByQueueName(queue.getName()).forEach(araQueueMemberRepository::delete);
        araQueueRepository.deleteById(queue.getName());
        memberRepository.findByQueueId(id).forEach(memberRepository::delete);
        queueRepository.delete(queue);
    }

    @Transactional
    public CcQueueMember addMember(Long queueId, Long agentId) {
        return addMember(queueId, agentId, 0);
    }

    /**
     * Prioridade (Fase 12.3): {@code penalty} do Asterisk — menor valor é atendido antes. A UI
     * expõe como "Prioridade 1-9 (menor = antes)"; aqui aceita qualquer inteiro não-negativo, sem
     * reimpor a faixa 1-9 no backend (é convenção da UI, não limite real do Asterisk).
     */
    @Transactional
    public CcQueueMember addMember(Long queueId, Long agentId, int penalty) {
        validatePenalty(penalty);
        var queue = findById(queueId);
        var agent = findAgentInScope(agentId);
        memberRepository
                .findByQueueIdAndAgentId(queueId, agentId)
                .ifPresent(
                        m -> {
                            throw new IllegalArgumentException("Agente já está nesta fila.");
                        });
        var extension =
                extensionRepository
                        .findByAgentId(agentId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Agente sem ramal provisionado: " + agentId));

        var member =
                memberRepository.save(
                        CcQueueMember.builder().queue(queue).agent(agent).penalty(penalty).build());

        araQueueMemberRepository.save(
                AraQueueMember.builder()
                        .queueName(queue.getName())
                        .interfaceName("PJSIP/" + extension.getExtension())
                        .memberName(agent.getName())
                        .stateInterface("PJSIP/" + extension.getExtension())
                        .penalty(penalty)
                        .paused(0)
                        .build());

        return member;
    }

    /**
     * Atualiza a prioridade de um membro já existente, espelhando em ARA (é o campo que o
     * Asterisk realmente lê — sem espelhar, a UI mentiria sobre a prioridade real da chamada).
     */
    @Transactional
    public CcQueueMember updateMemberPenalty(Long queueId, Long agentId, int penalty) {
        validatePenalty(penalty);
        var queue = findById(queueId);
        var member =
                memberRepository
                        .findByQueueIdAndAgentId(queueId, agentId)
                        .orElseThrow(() -> new IllegalArgumentException("Agente não está nesta fila."));
        member.setPenalty(penalty);
        var saved = memberRepository.save(member);

        var extension = extensionRepository.findByAgentId(agentId);
        extension.ifPresent(
                ext ->
                        araQueueMemberRepository
                                .findByQueueNameAndInterfaceName(queue.getName(), "PJSIP/" + ext.getExtension())
                                .ifPresent(
                                        araMember -> {
                                            araMember.setPenalty(penalty);
                                            araQueueMemberRepository.save(araMember);
                                        }));
        return saved;
    }

    private void validatePenalty(int penalty) {
        if (penalty < 0) {
            throw new IllegalArgumentException("Prioridade não pode ser negativa.");
        }
    }

    @Transactional
    public void removeMember(Long queueId, Long agentId) {
        var queue = findById(queueId);
        var member =
                memberRepository
                        .findByQueueIdAndAgentId(queueId, agentId)
                        .orElseThrow(() -> new IllegalArgumentException("Agente não está nesta fila."));
        var extension = extensionRepository.findByAgentId(agentId);
        extension.ifPresent(
                ext ->
                        araQueueMemberRepository.deleteByQueueNameAndInterfaceName(
                                queue.getName(), "PJSIP/" + ext.getExtension()));
        memberRepository.delete(member);
    }

    /**
     * Remove um agente de todas as filas em que estiver — usado ao desativar um atendente
     * (Fase 12.1): sem isso o Asterisk continua tocando um ramal desligado, e a chamada fica sem
     * resposta até o timeout da fila. Sem escopo de BU aqui de propósito — quem chama
     * (CallCenterAgentProvisioningService.deactivateForUser) já resolveu o agente por userId, não
     * por um id vindo de fora vulnerável a enumeração.
     */
    @Transactional
    public void removeFromAllQueues(Long agentId) {
        memberRepository.findByAgentId(agentId).forEach(member -> removeMember(member.getQueue().getId(), agentId));
    }

    /** Filas de um agente (Fase 12.4) — findAgentInScope aplica o mesmo guard de BU usado por
     * addMember/removeMember antes de listar. */
    @Transactional(readOnly = true)
    public List<CcQueueMember> queuesOfAgent(Long agentId) {
        findAgentInScope(agentId);
        return memberRepository.findByAgentId(agentId);
    }

    @Transactional(readOnly = true)
    public List<CcQueueMember> members(Long queueId) {
        // Achado de segurança (security-reviewer): findById aplica o escopo por BU — sem essa
        // chamada, um usuário restrito conseguia enumerar agentes/ramal de fila de outra BU só
        // sabendo o id (sequencial). O retorno de findById é descartado de propósito: só serve
        // pra forçar o guard, o resultado real vem do memberRepository.
        findById(queueId);
        return memberRepository.findByQueueId(queueId);
    }

    private String resolveStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return "ringall";
        }
        if (!VALID_STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("Estratégia de fila inválida: " + strategy);
        }
        return strategy;
    }

    private int resolveTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return 15;
        }
        if (timeoutSeconds < 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    "Timeout deve estar entre 0 e " + MAX_TIMEOUT_SECONDS + " segundos.");
        }
        return timeoutSeconds;
    }

    /**
     * Normaliza string vazia para null — evita ambiguidade entre "sem aviso" e "path em branco".
     * Achado de segurança (security-reviewer): o valor é interpolado sem sanitização no
     * {@code Playback(${CONSENT_PATH})} do dialplan — restringe a um diretório fixo (mesma defesa
     * de path traversal usada em {@code CallCenterRecordingService.resolveAudioFile}) para que um
     * admin com PERM_WRITE_callcenter.filas não consiga apontar o dialplan para um arquivo
     * arbitrário do container Asterisk.
     */
    private String normalizeConsentPath(String consentMessagePath) {
        if (consentMessagePath == null || consentMessagePath.isBlank()) {
            return null;
        }
        String trimmed = consentMessagePath.trim();
        String consentBaseDir = recordingBasePath + "/avisos";
        try {
            java.io.File base = new java.io.File(consentBaseDir).getCanonicalFile();
            java.io.File target = new java.io.File(trimmed).getCanonicalFile();
            String basePath = base.getPath() + java.io.File.separator;
            if (!target.getPath().startsWith(basePath)) {
                throw new IllegalArgumentException(
                        "Caminho do aviso de gravação deve estar dentro de " + consentBaseDir);
            }
            return target.getPath();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Caminho do aviso de gravação inválido: " + trimmed);
        }
    }

    private void validateExtensionRange(String name) {
        int num;
        try {
            num = Integer.parseInt(name.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Número de fila inválido: " + name);
        }
        if (num < RANGE_START || num > RANGE_END) {
            throw new IllegalArgumentException(
                    "Filas devem usar um número entre " + RANGE_START + " e " + RANGE_END + ".");
        }
    }

    /**
     * Achado de segurança (security-reviewer): validava só que a BU existia, não que o usuário
     * restrito tinha acesso a ela — permitia mover uma fila para uma BU que o usuário não
     * gerencia. Mesmo tipo de checagem já aplicada em findById/findAgentInScope.
     */
    private BusinessUnit resolveBusinessUnit(Integer businessUnitId) {
        if (businessUnitId == null) {
            return null;
        }
        if (BusinessUnitContext.isRestricted()
                && !BusinessUnitContext.currentBusinessUnitIds().contains(businessUnitId)) {
            throw new IllegalArgumentException("BU não encontrada: " + businessUnitId);
        }
        return businessUnitRepository
                .findById(businessUnitId)
                .orElseThrow(() -> new IllegalArgumentException("BU não encontrada: " + businessUnitId));
    }

    private Specification<CcQueue> businessUnitScope() {
        if (!BusinessUnitContext.isRestricted()) {
            return null;
        }
        return CallCenterSpecifications.queueRestrictedToBusinessUnits(
                BusinessUnitContext.currentBusinessUnitIds());
    }
}
