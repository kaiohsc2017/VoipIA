package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.interaction.CcDisposition;
import com.asteriskia.domain.masterdata.BusinessUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcChatSession — uma conversa de chat, da entrada na fila até o encerramento (Fase 7a).
 * Equivalente chat de {@link com.asteriskia.domain.callcenter.interaction.CcInteraction}, que é
 * estritamente de voz (amarrada a {@code channelUniqueId} do Asterisk) — não dá pra reaproveitar
 * aquela tabela aqui. A unificação de timeline voz+chat é trabalho da Fase 9.
 */
@Entity
@Table(name = "cc_chat_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "channel_id", nullable = false)
    private CcChatChannel channel;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "queue_id", nullable = false)
    private CcQueue queue;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Column(name = "customer_ref", nullable = false, length = 120)
    private String customerRef;

    /** Fase 7e — chat_id do Telegram (ou outro identificador externo de canal futuro) que
     * originou esta sessão; {@code null} para webchat/simulador. Combinado com {@code channel_id}
     * num índice único parcial (só {@code closed_at IS NULL}) para nunca existirem duas sessões
     * simultâneas abertas para o mesmo chat_id no mesmo canal (V79). */
    @Column(name = "external_ref", length = 120)
    private String externalRef;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    /** Fase 14 — {@code sam_account_name} do AD resolvido pela cascata de identificação
     * ({@code CallCenterIdentityResolver}), ou {@code null} se não resolvido (estado normal). */
    @Column(name = "resolved_ad_sam", length = 128)
    private String resolvedAdSam;

    /** Espelha {@code IdentitySource} — {@code null} junto com {@code resolvedAdSam} nulo. */
    @Column(name = "identity_source", length = 20)
    private String identitySource;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "waiting";

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "assigned_agent_id")
    private CcAgent assignedAgent;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "disposition_id")
    private CcDisposition disposition;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /**
     * Caminho do transcript (.json/.txt) exportado em /opt/VoipIA/media/chat ao encerrar a sessão
     * (Fase 11 do plano omnicanal) — {@code null} até a exportação assíncrona concluir.
     */
    @Column(name = "transcript_path", length = 255)
    private String transcriptPath;
}
