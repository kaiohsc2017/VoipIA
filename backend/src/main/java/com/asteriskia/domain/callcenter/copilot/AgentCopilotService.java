package com.asteriskia.domain.callcenter.copilot;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.kb.CallCenterKbChunkDao;
import com.asteriskia.domain.callcenter.kb.CallCenterKbEmbeddingClient;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCopilotService {

    private final CcAgentRepository agentRepository;
    private final CcAgentCopilotLogRepository copilotLogRepository;
    private final CallCenterKbEmbeddingClient embeddingClient;
    private final CallCenterKbChunkDao chunkDao;
    private final SimpMessagingTemplate messagingTemplate;

    public CopilotSuggestionDto processLiveTurn(Long agentId, String interactionId, String customerUtterance) {
        if (customerUtterance == null || customerUtterance.trim().isEmpty()) {
            return null;
        }

        CcAgent agent = (agentId != null) ? agentRepository.findById(agentId).orElse(null) : null;

        String suggestedResponse;
        String citation = null;
        double confidence = 0.50;

        try {
            String vectorLiteral = embeddingClient.embedAsVectorLiteral(customerUtterance.trim());
            var matches = chunkDao.searchTopK(vectorLiteral, 3);

            if (matches != null && !matches.isEmpty() && matches.get(0).similarity() >= 0.35) {
                var best = matches.get(0);
                citation = best.citation();
                confidence = Math.round(best.similarity() * 1000.0) / 10.0;
                suggestedResponse = "Com base em '" + citation + "': " + best.chunkText();
            } else {
                suggestedResponse = "Entendi sua solicitação. Um momento, por favor, enquanto verifico as informações no sistema.";
            }
        } catch (Exception e) {
            log.warn("Copiloto: fallback em razão de indisponibilidade de embeddings: {}", e.getMessage());
            suggestedResponse = "Obrigado por aguardar. Como posso prosseguir para ajudá-lo com esta questão?";
        }

        CcAgentCopilotLog logEntry = CcAgentCopilotLog.builder()
                .agent(agent)
                .interactionId(interactionId)
                .customerUtterance(customerUtterance)
                .suggestedResponse(suggestedResponse)
                .confidenceScore(confidence)
                .createdAt(Instant.now())
                .build();

        logEntry = copilotLogRepository.save(logEntry);

        CopilotSuggestionDto dto = new CopilotSuggestionDto(
                logEntry.getId(),
                agentId,
                interactionId,
                customerUtterance,
                suggestedResponse,
                citation,
                confidence,
                logEntry.getCreatedAt()
        );

        // Notificação em tempo real via WebSocket STOMP diretamente para o Desktop do Agente
        if (agentId != null) {
            try {
                messagingTemplate.convertAndSend("/topic/callcenter/agent/" + agentId + "/copilot", dto);
            } catch (Exception wsEx) {
                log.debug("Não foi possível enviar sugestão do copiloto via WS para o agente {}: {}", agentId, wsEx.getMessage());
            }
        }

        return dto;
    }

    @Transactional
    public boolean registerFeedback(Long logId, String feedback) {
        return copilotLogRepository.findById(logId)
                .map(entry -> {
                    entry.setAgentFeedback(feedback);
                    copilotLogRepository.save(entry);
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<CopilotSuggestionDto> getHistoryForAgent(Long agentId) {
        return copilotLogRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .limit(30)
                .map(l -> new CopilotSuggestionDto(
                        l.getId(),
                        l.getAgent() != null ? l.getAgent().getId() : null,
                        l.getInteractionId(),
                        l.getCustomerUtterance(),
                        l.getSuggestedResponse(),
                        l.getSuggestedKbArticle() != null ? l.getSuggestedKbArticle().getTitle() : null,
                        l.getConfidenceScore(),
                        l.getCreatedAt()
                ))
                .toList();
    }

    public record CopilotSuggestionDto(
            Long logId,
            Long agentId,
            String interactionId,
            String customerUtterance,
            String suggestedResponse,
            String kbCitation,
            Double confidenceScore,
            Instant createdAt
    ) {}
}
