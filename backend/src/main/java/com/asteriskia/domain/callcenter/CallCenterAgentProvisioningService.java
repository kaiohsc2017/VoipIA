package com.asteriskia.domain.callcenter;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterAgentProvisioningService — provisiona um {@link CcAgent} completo (ramal + filas) a
 * partir do cadastro de um usuário com perfil de atendente (Fase 12.1 do plano omnicanal — é o
 * desbloqueador de toda validação real do módulo, hoje sem nenhum agente cadastrado).
 *
 * <p>Reusa {@link CallCenterAgentService#create} para o provisionamento ARA (ps_endpoints/
 * ps_auths/ps_aors) em vez de duplicar {@code provisionAra}/{@code deprovisionAra} — esses
 * métodos são privados de propósito ali; o caminho de reuso é chamar o service público, não
 * copiar a lógica.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterAgentProvisioningService {

    private final CcExtensionRepository extensionRepository;
    private final CallCenterAgentService agentService;
    private final CallCenterQueueService queueService;
    private final CcAgentRepository agentRepository;

    /**
     * Aloca o próximo ramal livre no range vigente de agente (Fase 19 — configurável, default
     * {@code 4000-4999}), cria o {@link CcAgent} (reusando {@link
     * CallCenterAgentService#create}) e insere o agente em cada fila da lista, com a prioridade
     * informada. Idempotente por usuário: se já existir um {@link CcAgent} para este {@code
     * userId} (índice único de V61), falha com mensagem clara em vez de duplicar.
     */
    @Transactional
    public CcAgent provisionForUser(
            Integer userId, String displayName, Integer businessUnitId, List<QueueMembershipRequest> memberships) {
        agentRepository
                .findByUserId(userId)
                .ifPresent(
                        existing -> {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Usuário " + userId + " já possui um agente do Call Center (id="
                                            + existing.getId() + ").");
                        });

        // Fase 19 (Parte III): faixa lida de CcSettingsService (default 4000-4999 se nunca
        // configurada) — deixou de ser constante estática para permitir range configurável.
        var range = agentService.extensionRange();
        Integer nextExtension = extensionRepository.findNextExtension(range.start(), range.end());
        if (nextExtension == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Faixa de ramais de agente (" + range.start() + "-" + range.end() + ") está esgotada.");
        }

        CcAgent agent;
        try {
            agent =
                    agentService.create(
                            new AgentRequest(displayName, userId, businessUnitId, String.valueOf(nextExtension)));

            if (memberships != null) {
                for (var membership : memberships) {
                    int priority = membership.priority() != null ? membership.priority() : 0;
                    queueService.addMember(membership.queueId(), agent.getId(), priority);
                }
            }
        } catch (IllegalArgumentException e) {
            // agentService/queueService lançam IllegalArgumentException (regra de negócio interna
            // dos dois services) — convertido aqui para ResponseStatusException, senão o
            // GlobalExceptionHandler trataria como erro interno genérico (500), mascarando a causa
            // real de uma falha de provisionamento (ex: fila inexistente na lista de memberships).
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        log.info(
                "Atendente provisionado a partir do usuário {}: agentId={} ramal={}",
                userId, agent.getId(), nextExtension);
        return agent;
    }

    /**
     * Desprovisiona o agente de um usuário desativado (Fase 12.1): remove das filas ARA e
     * desativa o {@link CcAgent}, mas **preserva a linha** — histórico de relatórios (Fase 9)
     * referencia {@code cc_agents.id}, apagar quebraria agregados já calculados.
     */
    @Transactional
    public void deactivateForUser(Integer userId) {
        agentRepository
                .findByUserId(userId)
                .ifPresent(
                        agent -> {
                            queueService.removeFromAllQueues(agent.getId());
                            agent.setActive(false);
                            agentRepository.save(agent);
                            log.info("Agente do Call Center desativado (usuário {} desativado): agentId={}",
                                    userId, agent.getId());
                        });
    }
}
