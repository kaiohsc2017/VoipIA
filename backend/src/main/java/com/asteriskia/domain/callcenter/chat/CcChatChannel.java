package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.flow.CcFlow;
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
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcChatChannel — canal de origem de uma sessão de chat (Fase 7a: só o simulador interno
 * {@code internal_test}; webchat chega na Fase 7b, com o esquema de autenticação anônima do
 * cliente final). Fase 24: ganha {@link #defaultQueue} (substitui a variável de ambiente única
 * {@code CALLCENTER_CHAT_PUBLIC_QUEUE_ID}) e, opcionalmente, {@link #botFlow} — um fluxo do Flow
 * Builder (canal {@code chat}) que atende a sessão antes de chegar a um agente humano.
 *
 * <p>"público"/{@code public} no código (rota, propriedade legada) é vocabulário da Fase 7b —
 * decisão D8 do plano já esclarece que a aplicação nunca vai à internet aberta, roda dentro da
 * rede corporativa; "widget público" aqui sempre significou "widget interno".
 */
@Entity
@Table(name = "cc_chat_channels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcChatChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    /** Só {@code webchat} por ora (Fase 24) — WhatsApp/Telegram exigem credencial externa que o
     * projeto não tem, mesmo gap já registrado para outras integrações (Jira). */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String type = "webchat";

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "default_queue_id")
    private CcQueue defaultQueue;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "bot_flow_id")
    private CcFlow botFlow;

    @Column(name = "greeting_message", columnDefinition = "text")
    private String greetingMessage;

    @Column(name = "away_message", columnDefinition = "text")
    private String awayMessage;

    @Builder.Default
    private Boolean active = true;

    /** Anexos (Fase 7d, D6) — cota total por uploader (2GB default) e janela de retenção (10
     * dias default), configuráveis na criação/edição do canal. */
    @Builder.Default
    @Column(name = "attachment_quota_bytes", nullable = false)
    private Long attachmentQuotaBytes = 2_147_483_648L;

    @Builder.Default
    @Column(name = "attachment_retention_days", nullable = false)
    private Integer attachmentRetentionDays = 10;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
