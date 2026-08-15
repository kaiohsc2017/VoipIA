package com.asteriskia.domain.callcenter.ia;

import com.asteriskia.config.ResourceNotFoundException;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterIaAgentService — CRUD das configurações de persona/prompt/modelo do nó "agente_ia"
 * (Fase A). Validação de {@code model} por allowlist (nunca string livre repassada à API do
 * Gemini) — mesmos modelos de texto já usados em outros pontos do domínio Call Center
 * (ContactProfileGenerator/CallCenterNpsTranscriptionScheduler/CallCenterKbAnswerService).
 */
@Service
@RequiredArgsConstructor
public class CallCenterIaAgentService {

    private static final Set<String> ALLOWED_MODELS =
            Set.of("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro");

    private static final BigDecimal MIN_TEMPERATURE = BigDecimal.ZERO;
    private static final BigDecimal MAX_TEMPERATURE = new BigDecimal("2");
    private static final int MIN_MAX_TURNS = 1;
    private static final int MAX_MAX_TURNS = 20;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 50;
    private static final BigDecimal MIN_MATCH_THRESHOLD = BigDecimal.ZERO;
    private static final BigDecimal MAX_MATCH_THRESHOLD = BigDecimal.ONE;

    private final CcIaAgentRepository repository;
    private final CcQueueRepository queueRepository;

    public List<CcIaAgent> list(boolean activeOnly) {
        return activeOnly ? repository.findByActiveTrueOrderByNameAsc() : repository.findAllByOrderByNameAsc();
    }

    public CcIaAgent getById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Agente de IA não encontrado: " + id));
    }

    public CcIaAgent create(IaAgentRequest request) {
        validateName(request.name(), null);
        var agent =
                CcIaAgent.builder()
                        .name(request.name().trim())
                        .description(request.description())
                        .systemPrompt(request.systemPrompt())
                        .greeting(request.greeting())
                        .active(true)
                        .build();
        applyRequest(agent, request);
        return repository.save(agent);
    }

    public CcIaAgent update(Long id, IaAgentRequest request) {
        var agent = getById(id);
        validateName(request.name(), id);
        agent.setName(request.name().trim());
        agent.setDescription(request.description());
        agent.setSystemPrompt(request.systemPrompt());
        agent.setGreeting(request.greeting());
        applyRequest(agent, request);
        return repository.save(agent);
    }

    /** Soft delete — nunca remove a linha (pode estar referenciada por um fluxo já publicado). */
    public void delete(Long id) {
        var agent = getById(id);
        agent.setActive(false);
        repository.save(agent);
    }

    private void applyRequest(CcIaAgent agent, IaAgentRequest request) {
        if (!ALLOWED_MODELS.contains(request.model())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Modelo não suportado: " + request.model());
        }
        agent.setModel(request.model());
        agent.setTemperature(clamp(request.temperature(), MIN_TEMPERATURE, MAX_TEMPERATURE, new BigDecimal("0.20")));
        agent.setTopK(clampInt(request.topK(), MIN_TOP_K, MAX_TOP_K, null));
        agent.setMatchThreshold(
                clamp(request.matchThreshold(), MIN_MATCH_THRESHOLD, MAX_MATCH_THRESHOLD, null));
        agent.setKbTags(request.kbTags());
        agent.setMaxTurns(clampInt(request.maxTurns(), MIN_MAX_TURNS, MAX_MAX_TURNS, 5));
        var maxCost = request.maxCostUsd() == null ? new BigDecimal("0.10") : request.maxCostUsd();
        if (maxCost.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxCostUsd não pode ser negativo.");
        }
        agent.setMaxCostUsd(maxCost);
        if (request.fallbackQueueId() != null) {
            var queue =
                    queueRepository
                            .findById(request.fallbackQueueId())
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.BAD_REQUEST,
                                                    "Fila de fallback não encontrada: " + request.fallbackQueueId()));
            agent.setFallbackQueue(queue);
        } else {
            agent.setFallbackQueue(null);
        }
    }

    private void validateName(String name, Long ignoreId) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome é obrigatório.");
        }
        boolean duplicate =
                ignoreId == null
                        ? repository.existsByNameIgnoreCase(name.trim())
                        : repository.existsByNameIgnoreCaseAndIdNot(name.trim(), ignoreId);
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um Agente de IA com este nome.");
        }
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.compareTo(min) < 0) {
            return min;
        }
        if (value.compareTo(max) > 0) {
            return max;
        }
        return value;
    }

    private Integer clampInt(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private Integer clampInt(Integer value, int min, int max, Integer fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
