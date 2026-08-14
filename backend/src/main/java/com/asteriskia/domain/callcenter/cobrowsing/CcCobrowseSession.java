package com.asteriskia.domain.callcenter.cobrowsing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcCobrowseSession — registro de co-browsing gravado de uma {@code CcChatSession} (Fase 17).
 * Nesta sub-fase (17a) só existe o estado de consentimento — {@code filePath}/{@code sizeBytes}/
 * {@code eventCount}/{@code truncated} ficam zerados/nulos até a captura real (17b) existir.
 * Relação 1:1 com a sessão de chat via {@code chatSessionId} (Long puro, mesmo padrão de
 * {@link com.asteriskia.domain.callcenter.chat.CcChatMessage#getSessionId()} — não precisa
 * carregar a entidade inteira só para gravar/consultar consentimento).
 */
@Entity
@Table(name = "cc_cobrowse_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcCobrowseSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_session_id", nullable = false, unique = true)
    private Long chatSessionId;

    @Column(name = "business_unit_id")
    private Long businessUnitId;

    @Builder.Default
    @Column(name = "consent_status", nullable = false, length = 20)
    private String consentStatus = "pending";

    @Column(name = "consent_at")
    private LocalDateTime consentAt;

    @Column(name = "consent_text_hash", length = 64)
    private String consentTextHash;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "file_path", length = 255)
    private String filePath;

    @Builder.Default
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes = 0L;

    @Builder.Default
    @Column(name = "event_count", nullable = false)
    private Integer eventCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean truncated = false;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "last_event_at")
    private LocalDateTime lastEventAt;

    @Column(name = "purged_at")
    private LocalDateTime purgedAt;
}
