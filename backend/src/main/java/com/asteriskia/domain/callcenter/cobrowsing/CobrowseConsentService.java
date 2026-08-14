package com.asteriskia.domain.callcenter.cobrowsing;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.chat.CcChatSession;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CobrowseConsentService — ciclo de vida do <b>consentimento</b> de co-browsing gravado do chat
 * (Fase 17, sub-fase 17a). Nesta fatia não existe captura real (rrweb — 17b) nem arquivo físico
 * a apagar (17c/17d) — "revogar" aqui é só marcar o estado, não deletar disco.
 *
 * <p>Regra central: sem {@code CcAgent.cobrowseEnabled=true}, nenhuma {@link CcCobrowseSession}
 * é criada — sem sessão, o endpoint de consentimento responde 404 (nunca 403 — mesmo padrão de
 * {@code CallCenterRecordingController}, não revela se o chat existe ou não a quem tenta um id
 * arbitrário).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CobrowseConsentService {

    private final CcCobrowseSessionRepository cobrowseSessionRepository;

    /**
     * Chamado quando o agente assume (claim) um chat — cria a sessão de cobrowse só se o agente
     * tiver o toggle ligado. Idempotente: se já existir uma sessão para este chat (ex: reclaim),
     * não duplica (a coluna {@code chat_session_id} é UNIQUE).
     */
    @Transactional
    public void ensureSessionForClaim(CcChatSession chatSession, CcAgent agent) {
        if (agent == null || !Boolean.TRUE.equals(agent.getCobrowseEnabled())) {
            return;
        }
        if (cobrowseSessionRepository.findByChatSessionId(chatSession.getId()).isPresent()) {
            return;
        }
        Long businessUnitId = chatSession.getBusinessUnit() != null
                ? chatSession.getBusinessUnit().getId().longValue() : null;
        cobrowseSessionRepository.save(CcCobrowseSession.builder()
                .chatSessionId(chatSession.getId())
                .businessUnitId(businessUnitId)
                .consentStatus("pending")
                .startedAt(LocalDateTime.now())
                .build());
        log.info("Sessão de co-browsing criada (pendente de consentimento): chatSessionId={}", chatSession.getId());
    }

    /**
     * Registra a decisão do cliente (aceite/recusa) — {@code textHash} é o SHA-256 (hex) do
     * texto de consentimento exibido no widget, nunca o texto em si.
     */
    @Transactional
    public CcCobrowseSession registerConsent(Long chatSessionId, boolean granted, String textHash) {
        CcCobrowseSession session = findOrThrow(chatSessionId);
        if ("revoked".equals(session.getConsentStatus())) {
            // Consentimento já revogado — não reabre; o cliente precisaria de uma nova sessão de chat.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Consentimento já foi revogado para esta conversa.");
        }
        if (granted) {
            session.setConsentStatus("granted");
        } else if ("granted".equals(session.getConsentStatus())) {
            // Já tinha aceitado antes e agora recusa/revoga em seguida — trata como revogação.
            session.setConsentStatus("revoked");
            session.setRevokedAt(LocalDateTime.now());
            session.setPurgedAt(LocalDateTime.now());
        } else {
            session.setConsentStatus("denied");
        }
        session.setConsentAt(LocalDateTime.now());
        session.setConsentTextHash(textHash);
        return cobrowseSessionRepository.save(session);
    }

    /**
     * Revogação explícita (ex: botão "parar captura" sempre visível durante a sessão) — marca
     * {@code revoked_at}/{@code purged_at} e é seguro chamar mais de uma vez (idempotente): a
     * segunda chamada não sobrescreve o timestamp da primeira revogação.
     */
    @Transactional
    public CcCobrowseSession revoke(Long chatSessionId) {
        CcCobrowseSession session = findOrThrow(chatSessionId);
        if ("revoked".equals(session.getConsentStatus())) {
            return session;
        }
        session.setConsentStatus("revoked");
        session.setRevokedAt(LocalDateTime.now());
        session.setPurgedAt(LocalDateTime.now());
        return cobrowseSessionRepository.save(session);
    }

    private CcCobrowseSession findOrThrow(Long chatSessionId) {
        return cobrowseSessionRepository.findByChatSessionId(chatSessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessão de co-browsing não encontrada."));
    }
}
