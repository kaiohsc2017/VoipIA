package com.asteriskia.domain.callcenter.flow;

import com.asteriskia.domain.callcenter.CcSettingsService;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterFlowService — CRUD, versionamento (rascunho/publicação) e rollback dos fluxos do Flow
 * Builder (Fase 5a). Publicar sempre: valida o rascunho, arquiva a versão PUBLISHED anterior (se
 * existir), promove o rascunho a PUBLISHED e cria um novo rascunho como cópia — a versão
 * PUBLISHED/ARCHIVED nunca sofre UPDATE no grafo, garantindo que uma chamada em curso não mude de
 * comportamento no meio (a Fase 5b é quem de fato vai ler {@code publishedVersionId} em execução).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterFlowService {

    private static final String DEFAULT_EMPTY_GRAPH =
            "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}";

    private final CcFlowRepository flowRepository;
    private final CcFlowVersionRepository versionRepository;
    private final BusinessUnitRepository businessUnitRepository;
    private final FlowGraphValidator graphValidator;
    private final CcSettingsService settingsService;

    /** Range vigente de ramal de fluxo (Fase 19 — configurável, default 6000-6999). */
    private void validateExtensionRange(String entryExtension) {
        if (entryExtension == null) {
            return;
        }
        int num;
        try {
            num = Integer.parseInt(entryExtension.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ramal de fluxo inválido: " + entryExtension);
        }
        var range = settingsService.getRange(CcSettingsService.RangeType.FLOW);
        if (num < range.start() || num > range.end()) {
            throw new IllegalArgumentException(
                    "Ramais de fluxo devem usar um número entre " + range.start() + " e " + range.end() + ".");
        }
    }

    @Transactional(readOnly = true)
    public List<CcFlow> findAll() {
        Specification<CcFlow> spec = businessUnitScope();
        return spec == null ? flowRepository.findAll() : flowRepository.findAll(spec);
    }

    @Transactional(readOnly = true)
    public CcFlow findById(Long id) {
        var flow = flowRepository.findById(id).orElseThrow(() -> notFound(id));
        if (!inBusinessUnitScope(flow)) {
            throw notFound(id);
        }
        return flow;
    }

    @Transactional
    public CcFlow create(FlowRequest request) {
        var name = request.name().trim();
        flowRepository
                .findByName(name)
                .ifPresent(
                        f -> {
                            throw new IllegalArgumentException("Já existe um fluxo chamado \"" + name + "\".");
                        });
        validateExtensionRange(request.entryExtension());
        if (request.entryExtension() != null) {
            flowRepository
                    .findByEntryExtension(request.entryExtension())
                    .ifPresent(
                            f -> {
                                throw new IllegalArgumentException(
                                        "Ramal " + request.entryExtension() + " já está em uso por outro fluxo.");
                            });
        }

        var flow =
                CcFlow.builder()
                        .name(name)
                        .description(request.description())
                        .channel(request.channel())
                        .entryExtension(request.entryExtension())
                        .businessUnit(resolveBusinessUnit(request.businessUnitId()))
                        .active(true)
                        .createdBy(currentUsername())
                        .updatedBy(currentUsername())
                        .build();
        flow = flowRepository.save(flow);

        var draft =
                versionRepository.save(
                        CcFlowVersion.builder()
                                .flow(flow)
                                .versionNumber(1)
                                .status(FlowStatus.DRAFT)
                                .graph(DEFAULT_EMPTY_GRAPH)
                                .build());

        log.info("Fluxo criado: id={} nome={} versaoDraft={}", flow.getId(), name, draft.getId());
        return flow;
    }

    @Transactional
    public CcFlow update(Long id, FlowRequest request) {
        var flow = findById(id);
        var name = request.name().trim();
        flowRepository
                .findByName(name)
                .filter(f -> !f.getId().equals(id))
                .ifPresent(
                        f -> {
                            throw new IllegalArgumentException("Já existe um fluxo chamado \"" + name + "\".");
                        });
        validateExtensionRange(request.entryExtension());
        if (request.entryExtension() != null) {
            flowRepository
                    .findByEntryExtension(request.entryExtension())
                    .filter(f -> !f.getId().equals(id))
                    .ifPresent(
                            f -> {
                                throw new IllegalArgumentException(
                                        "Ramal " + request.entryExtension() + " já está em uso por outro fluxo.");
                            });
        }
        flow.setName(name);
        flow.setDescription(request.description());
        flow.setChannel(request.channel());
        flow.setEntryExtension(request.entryExtension());
        flow.setBusinessUnit(resolveBusinessUnit(request.businessUnitId()));
        flow.setUpdatedBy(currentUsername());
        return flowRepository.save(flow);
    }

    /** Salva o grafo na versão DRAFT atual — só avisos bloqueiam (nunca erros de publicação). */
    @Transactional
    public FlowGraphValidationResult saveDraft(Long flowId, String graphJson) {
        var flow = findById(flowId);
        var draft =
                versionRepository
                        .findByFlowIdAndStatus(flowId, FlowStatus.DRAFT)
                        .orElseThrow(
                                () -> new IllegalStateException("Fluxo sem rascunho ativo (estado inconsistente)."));
        var result = graphValidator.validate(graphJson, flow.getChannel(), false);
        draft.setGraph(graphJson);
        versionRepository.save(draft);
        return result;
    }

    /**
     * Publica o rascunho atual: valida com {@code forPublish=true} (bloqueia nó não implementado
     * e demais erros), arquiva a PUBLISHED anterior, promove o rascunho e cria um novo rascunho
     * cópia do grafo publicado.
     */
    @Transactional
    public FlowGraphValidationResult publish(Long flowId) {
        var flow = lockForUpdate(flowId);
        var draft =
                versionRepository
                        .findByFlowIdAndStatus(flowId, FlowStatus.DRAFT)
                        .orElseThrow(
                                () -> new IllegalStateException("Fluxo sem rascunho ativo (estado inconsistente)."));

        var result = graphValidator.validate(draft.getGraph(), flow.getChannel(), true);
        if (!result.isValid()) {
            return result;
        }

        versionRepository
                .findByFlowIdAndStatus(flowId, FlowStatus.PUBLISHED)
                .ifPresent(
                        previouslyPublished -> {
                            previouslyPublished.setStatus(FlowStatus.ARCHIVED);
                            versionRepository.save(previouslyPublished);
                        });

        var now = LocalDateTime.now();
        draft.setStatus(FlowStatus.PUBLISHED);
        draft.setPublishedAt(now);
        draft.setPublishedBy(currentUsername());
        versionRepository.save(draft);

        flow.setPublishedVersionId(draft.getId());
        flow.setUpdatedBy(currentUsername());
        flowRepository.save(flow);

        var nextVersionNumber =
                versionRepository.findTopByFlowIdOrderByVersionNumberDesc(flowId).map(v -> v.getVersionNumber() + 1).orElse(1);
        versionRepository.save(
                CcFlowVersion.builder()
                        .flow(flow)
                        .versionNumber(nextVersionNumber)
                        .status(FlowStatus.DRAFT)
                        .graph(draft.getGraph())
                        .build());

        log.info("Fluxo publicado: id={} versao={}", flowId, draft.getVersionNumber());
        return result;
    }

    /** Rollback: a versão alvo precisa ser ARCHIVED e pertencer ao fluxo; não edita nenhum grafo. */
    @Transactional
    public void rollback(Long flowId, Long versionId) {
        var flow = lockForUpdate(flowId);
        var target =
                versionRepository
                        .findById(versionId)
                        .filter(v -> v.getFlow().getId().equals(flowId))
                        .orElseThrow(
                                () -> new IllegalArgumentException("Versão " + versionId + " não pertence a este fluxo."));
        if (target.getStatus() != FlowStatus.ARCHIVED) {
            throw new IllegalArgumentException("Só é possível fazer rollback para uma versão arquivada.");
        }

        versionRepository
                .findByFlowIdAndStatus(flowId, FlowStatus.PUBLISHED)
                .ifPresent(
                        currentlyPublished -> {
                            currentlyPublished.setStatus(FlowStatus.ARCHIVED);
                            versionRepository.save(currentlyPublished);
                        });

        target.setStatus(FlowStatus.PUBLISHED);
        versionRepository.save(target);

        flow.setPublishedVersionId(target.getId());
        flow.setUpdatedBy(currentUsername());
        flowRepository.save(flow);

        log.info("Rollback do fluxo {} para a versão {}", flowId, target.getVersionNumber());
    }

    @Transactional(readOnly = true)
    public List<CcFlowVersion> listVersions(Long flowId) {
        findById(flowId);
        return versionRepository.findByFlowIdOrderByVersionNumberDesc(flowId);
    }

    @Transactional(readOnly = true)
    public CcFlowVersion findVersion(Long flowId, Long versionId) {
        findById(flowId);
        return versionRepository
                .findById(versionId)
                .filter(v -> v.getFlow().getId().equals(flowId))
                .orElseThrow(
                        () -> new IllegalArgumentException("Versão " + versionId + " não pertence a este fluxo."));
    }

    @Transactional
    public void delete(Long id) {
        var flow = findById(id);
        if (flow.getPublishedVersionId() != null) {
            throw new IllegalArgumentException(
                    "Não é possível excluir um fluxo com versão publicada — despublique antes.");
        }
        flowRepository.delete(flow);
    }

    /** Lock pessimista do fluxo, com o mesmo guard de BU de {@link #findById} — usado por
     * publish()/rollback() para impedir duas requisições concorrentes promovendo versões
     * diferentes a PUBLISHED do mesmo fluxo. */
    private CcFlow lockForUpdate(Long id) {
        var flow = flowRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (!inBusinessUnitScope(flow)) {
            throw notFound(id);
        }
        return flow;
    }

    private boolean inBusinessUnitScope(CcFlow flow) {
        if (!BusinessUnitContext.isRestricted()) {
            return true;
        }
        return flow.getBusinessUnit() == null
                || BusinessUnitContext.currentBusinessUnitIds().contains(flow.getBusinessUnit().getId());
    }

    private Specification<CcFlow> businessUnitScope() {
        if (!BusinessUnitContext.isRestricted()) {
            return null;
        }
        var buIds = BusinessUnitContext.currentBusinessUnitIds();
        return (root, query, cb) ->
                cb.or(root.get("businessUnit").isNull(), root.get("businessUnit").get("id").in(buIds));
    }

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

    private String currentUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "sistema" : authentication.getName();
    }

    // Fase 19 (Parte III) — antes lançava IllegalArgumentException, que caía no catch-all de
    // RuntimeException do GlobalExceptionHandler e virava 500 genérico para um id inexistente.
    // Mesma correção já aplicada em CallCenterAgentService/CallCenterQueueService.
    private org.springframework.web.server.ResponseStatusException notFound(Long id) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Fluxo não encontrado: " + id);
    }
}
