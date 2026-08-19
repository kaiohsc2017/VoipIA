package com.asteriskia.domain.callcenter.copilot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.kb.CallCenterKbChunkDao;
import com.asteriskia.domain.callcenter.kb.CallCenterKbEmbeddingClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class AgentCopilotServiceTest {

    @Mock
    private CcAgentRepository agentRepository;

    @Mock
    private CcAgentCopilotLogRepository copilotLogRepository;

    @Mock
    private CallCenterKbEmbeddingClient embeddingClient;

    @Mock
    private CallCenterKbChunkDao chunkDao;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private AgentCopilotService copilotService;

    @BeforeEach
    void setUp() {
        copilotService = new AgentCopilotService(agentRepository, copilotLogRepository, embeddingClient, chunkDao, messagingTemplate);
    }

    @Test
    void testProcessLiveTurnWithKbMatch() {
        CcAgent agent = CcAgent.builder().id(5L).build();
        when(agentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(embeddingClient.embedAsVectorLiteral("como alterar minha senha")).thenReturn("[0.1,0.2]");
        when(chunkDao.searchTopK("[0.1,0.2]", 3)).thenReturn(List.of(
                new CallCenterKbChunkDao.ChunkMatch("Acesse o menu Configurações e clique em Alterar Senha", 0.92, "Manual de Acesso", null)
        ));
        when(copilotLogRepository.save(any(CcAgentCopilotLog.class))).thenAnswer(i -> {
            CcAgentCopilotLog l = i.getArgument(0);
            l.setId(100L);
            return l;
        });

        var suggestion = copilotService.processLiveTurn(5L, "chat-123", "como alterar minha senha");

        assertNotNull(suggestion);
        assertEquals(100L, suggestion.logId());
        assertEquals("Manual de Acesso", suggestion.kbCitation());
        assertTrue(suggestion.suggestedResponse().contains("Manual de Acesso"));
        assertEquals(92.0, suggestion.confidenceScore());
        verify(messagingTemplate).convertAndSend(eq("/topic/callcenter/agent/5/copilot"), any(AgentCopilotService.CopilotSuggestionDto.class));
    }

    @Test
    void testRegisterFeedback() {
        CcAgentCopilotLog logEntry = CcAgentCopilotLog.builder().id(10L).build();
        when(copilotLogRepository.findById(10L)).thenReturn(Optional.of(logEntry));

        boolean ok = copilotService.registerFeedback(10L, "ACCEPTED");

        assertTrue(ok);
        assertEquals("ACCEPTED", logEntry.getAgentFeedback());
        verify(copilotLogRepository).save(logEntry);
    }
}
